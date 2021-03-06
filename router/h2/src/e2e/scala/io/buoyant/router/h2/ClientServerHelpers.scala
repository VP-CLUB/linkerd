package io.buoyant.router
package h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.{Status => _, param => fparam, _}
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.test.FunSuite
import java.net.InetSocketAddress
import org.scalatest.BeforeAndAfter
import scala.collection.JavaConverters._

trait ClientServerHelpers extends BeforeAndAfter { _: FunSuite =>

  val statsReceiver = new InMemoryStatsReceiver
  before {
    statsReceiver.clear()
  }
  after {
    statsReceiver.clear()
  }

  def reader(s: Stream) = s match {
    case Stream.Nil => fail("empty response stream")
    case r: Stream.Reader => r
  }

  def mkBuf(sz: Int): Buf =
    Buf.ByteArray.Owned(Array.fill[Byte](sz)(1.toByte))

  def withClient(srv: Request => Response)(f: Upstream => Unit): Unit = {
    val server = Downstream.mk("srv")(srv)
    val client = upstream(server.server)
    try f(client)
    finally {
      await(client.close())
      await(server.server.close())
    }
  }

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
    val dentry = Dentry(
      Path.read(s"/s/$name"),
      NameTree.read(s"/$$/inet/127.1/$port")
    )
  }

  object Downstream {
    def factory(name: String)(f: ClientConnection => Service[Request, Response]): Downstream = {
      val factory = new ServiceFactory[Request, Response] {
        def apply(conn: ClientConnection): Future[Service[Request, Response]] = Future(f(conn))
        def close(deadline: Time): Future[Unit] = Future.Done
      }
      val server = H2.server
        .configured(fparam.Label(name))
        .configured(fparam.Stats(statsReceiver.scope("srv")))
        .serve(":*", factory)
      Downstream(name, server)
    }

    def service(name: String)(f: Request=>Future[Response]): Downstream =
      factory(name) { _ => Service.mk[Request, Response](f) }

    def mk(name: String)(f: Request=>Response): Downstream =
      service(name) { req => Future(f(req)) }

    def const(name: String, value: String): Downstream =
      mk(name) { _ =>
        val q = new AsyncQueue[Frame]
        q.offer(Frame.Data.eos(Buf.Utf8(value)))
        Response(Status.Ok, Stream(q))
      }
  }

  case class Upstream(service: Service[Request, Response]) extends Closable {

    def apply(req: Request): Future[Response] = service(req)

    def get(host: String, path: String = "/")(check: Option[String] => Boolean): Unit = {
      val req = Request("http", Method.Get, host, path, Stream.Nil)
      val rsp = await(service(req))
      assert(rsp.status == Status.Ok)
      rsp.data match {
        case Stream.Nil =>
          assert(check(None))

        case stream: Stream.Reader =>
          await(stream.read()) match {
            case f: Frame.Data if f.isEnd =>
              val Buf.Utf8(data) = f.buf
              assert(check(Some(data)))
              await(f.release())

            case f => fail(s"unexpected frame: $f")
          }
      }
    }

    def close(d: Time) = service.close(d)
  }

  def upstream(server: ListeningServer): Upstream = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])
    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    val client = H2.client
      .configured(fparam.Stats(statsReceiver.scope("client")))
      .newClient(name, "upstream").toService
    Upstream(client)
  }

}

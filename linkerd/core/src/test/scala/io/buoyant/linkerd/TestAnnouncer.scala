package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Announcement}
import com.twitter.util.Future
import java.net.InetSocketAddress

class TestAnnouncerInitializer extends AnnouncerInitializer {
  override def configClass: Class[_] = classOf[TestAnnouncerConfig]
  override def configId: String = "io.l5d.test"
}

object TestAnnouncerInitializer extends TestAnnouncerInitializer

class TestAnnouncerConfig extends AnnouncerConfig {

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.test")

  override def mk(): Announcer = new TestAnnouncer
}

class TestAnnouncer extends Announcer {
  override val scheme: String = "test"

  var services: Map[Path, Set[InetSocketAddress]] = Map.empty

  override def announce(
    addr: InetSocketAddress,
    name: Path
  ): Future[Announcement] = synchronized {
    services = services + (name -> (services(name) + addr))
    Future.value(new Announcement {
      override def unannounce(): Future[Unit] = synchronized {
        services = services + (name -> (services(name) - addr))
        Future.Unit
      }
    })
  }
}

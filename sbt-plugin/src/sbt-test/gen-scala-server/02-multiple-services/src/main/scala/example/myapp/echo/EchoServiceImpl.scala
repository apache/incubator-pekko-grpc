package example.myapp.echo

import scala.concurrent.Future

import example.myapp.echo.grpc._

class EchoServiceImpl extends EchoService {
  def echo(in: EchoMessage): Future[EchoMessage] = Future.successful(in)
}

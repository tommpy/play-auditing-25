/*
 * Copyright 2016 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.play.audit.filters

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SubFlow}
import akka.stream.stage._
import akka.util.ByteString
import play.api.libs.streams.Accumulator
import play.api.mvc.{Result, _}
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.play.audit.EventTypes
import uk.gov.hmrc.play.audit.http.HttpAuditEvent
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.http.HeaderCarrier

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait AuditFilter extends EssentialFilter with HttpAuditEvent {

  def auditConnector: AuditConnector

  def controllerNeedsAuditing(controllerName: String): Boolean

  //These implicit declarations are a but fugly - should be replaced by injected
  //dependencies instead - for this the AuditFilter will need to be changed from
  //a trait to an abstract class
  implicit val system = ActorSystem("auditFilter")
  implicit val materializer = ActorMaterializer()

  protected def needsAuditing(request: RequestHeader): Boolean =
    (for (controllerName <- request.tags.get(play.routing.Router.Tags.ROUTE_CONTROLLER))
      yield controllerNeedsAuditing(controllerName)).getOrElse(true)

  protected def getBody(result: Result) = {
    val sink = Sink.fold[String, ByteString]("")({
      _ + _.decodeString("UTF-8")
    })
    result.body.dataStream.runWith(sink)
  }

  protected def onCompleteWithInput(next: Accumulator[ByteString, Result])(handler: (String, Try[Result]) => Unit): Accumulator[ByteString, Result] = {
    var requestBody: String = ""
    def callback(body: ByteString): Unit = {
      requestBody = body.decodeString("UTF-8")
    }

    //grabbed from plays csrf filter - seems the only way I can intercept the
    //request body source. Must be a better way, but this works at least.
    val wrappedAcc: Accumulator[ByteString, Result] = Accumulator(
      Flow[ByteString].transform(() => new BodyHandler(callback))
        .splitWhen(_ => false)
        .prefixAndTail(0)
        .map(_._2)
        .concatSubstreams
        .toMat(Sink.head[Source[ByteString, _]])(Keep.right)
    ).mapFuture { bodySource =>
      next.run(bodySource)
    }

    wrappedAcc.map { result =>
      handler(requestBody, Success(result))
      result
    }.recover[Result] {
      case ex: Throwable =>
        handler(requestBody, Failure(ex))
        throw ex
    }
  }

  def apply(nextFilter: EssentialAction) = new EssentialAction {
    def apply(requestHeader: RequestHeader) = {
      val next: Accumulator[ByteString, Result] = nextFilter(requestHeader)
      implicit val hc = HeaderCarrier.fromHeadersAndSession(requestHeader.headers)

      def performAudit(requestBody: String, maybeResult: Try[Result]): Unit = {
        maybeResult match {
          case Success(result) =>
            getBody(result) map { responseBody =>
              auditConnector.sendEvent(
                dataEvent(EventTypes.RequestReceived, requestHeader.uri, requestHeader)
                  .withDetail(ResponseMessage -> responseBody, StatusCode -> result.header.status.toString))
            }
          case Failure(f) =>
            auditConnector.sendEvent(
              dataEvent(EventTypes.RequestReceived, requestHeader.uri, requestHeader)
                .withDetail(FailedRequestMessage -> f.getMessage))
        }
      }

      if (needsAuditing(requestHeader)) {
        onCompleteWithInput(next)(performAudit)
      } else next
    }
  }
}

//Grabbed from plays csrf filter an augmented with a callback to capture the body
// has a fixed buffer size of 1024 atm
private class BodyHandler(callback: (ByteString) => Unit) extends DetachedStage[ByteString, ByteString] {
  var buffer: ByteString = ByteString.empty
  var next: ByteString = null
  var continue = false

  def onPush(elem: ByteString, ctx: DetachedContext[ByteString]) = {
    if (continue) {
      // Standard contract for forwarding as is in DetachedStage
      if (ctx.isHoldingDownstream) {
        ctx.pushAndPull(elem)
      } else {
        next = elem
        ctx.holdUpstream()
      }
    } else {
      if (buffer.size + elem.size > 10000) {
        // We've finished buffering up to the configured limit, try to capture
        buffer ++= elem
        // Switch to continue, and push the buffer
        continue = true
        if (ctx.isHoldingDownstream) {
          val toPush = buffer
          buffer = null
          ctx.pushAndPull(toPush)
        } else {
          next = buffer
          buffer = null
          ctx.holdUpstream()
        }
      } else {
        // Buffer
        buffer ++= elem
        ctx.pull()
      }
    }
  }

  def onPull(ctx: DetachedContext[ByteString]) = {
    if (continue) {
      // Standard contract for forwarding as is in DetachedStage
      if (next != null) {
        val toPush = next
        next = null
        if (ctx.isFinishing) {
          ctx.pushAndFinish(toPush)
        } else {
          ctx.pushAndPull(toPush)
        }
      } else {
        if (ctx.isFinishing) {
          ctx.finish()
        } else {
          ctx.holdDownstream()
        }
      }
    } else {
      // Otherwise hold because we're buffering
      ctx.holdDownstream()
    }
  }

  override def onUpstreamFinish(ctx: DetachedContext[ByteString]) = {
    if (continue) {
      if (next != null) {
        ctx.absorbTermination()
      } else {
        ctx.finish()
      }
    } else {
      next = buffer
      callback(buffer)
      buffer = null
      continue = true
      ctx.absorbTermination()
    }
  }
}
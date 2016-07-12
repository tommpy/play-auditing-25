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

package uk.gov.hmrc.play.audit.http

import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{OneInstancePerTest, Matchers, WordSpecLike}
import play.api.PlayException
import play.api.http.HttpErrorHandler
import play.api.mvc.{RequestHeader, Result}
import uk.gov.hmrc.play.audit.EventTypes
import uk.gov.hmrc.play.audit.http.config.{AuditConnectorProvider, AuditingErrorHandler}
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, MockAuditConnector}
import uk.gov.hmrc.play.http.{JsValidationException, NotFoundException}
import uk.gov.hmrc.play.test.Concurrent.await
import uk.gov.hmrc.play.test.DummyRequestHeader
import org.mockito.Matchers._
import play.api.mvc.Results._

import scala.concurrent.Future

class ErrorAuditingSettingsSpec extends WordSpecLike with Matchers with MockitoSugar with OneInstancePerTest {
  import EventTypes._

  val mockSuperHandler = mock[HttpErrorHandler]
  when(mockSuperHandler.onClientError(any(), any(), any())).thenReturn(Future.successful(Ok))
  when(mockSuperHandler.onServerError(any(), any())).thenReturn(Future.successful(Ok))

  trait ParentHandler extends HttpErrorHandler {
    override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = {
      mockSuperHandler.onClientError(request, statusCode, message)
    }

    override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
      mockSuperHandler.onServerError(request, exception)
    }
  }

  class TestErrorAuditing(val auditConnector: AuditConnector) extends ParentHandler
    with AuditingErrorHandler
    with HttpAuditEvent
    with AuditConnectorProvider {
    override lazy val appName = "app"
  }

  private val header: DummyRequestHeader = new DummyRequestHeader()
  "in a case of application error we" should {

    "send ServerInternalError event to DataStream for an Exception that occurred in the microservice" in {
      val mockConnector = new MockAuditConnector
      val auditing = new TestErrorAuditing(mockConnector)

      val resultF = auditing.onServerError(header, new PlayException("", "", new Exception("a generic application exception")))
      await(resultF)
      mockConnector.recordedEvent shouldNot be(None)
      mockConnector.recordedEvent.get.auditType shouldBe ServerInternalError
    }

    "send ResourceNotFound event to DataStream for a NotFoundException that occurred in the microservice" in {
      val mockConnector = new MockAuditConnector()
      val auditing = new TestErrorAuditing(mockConnector)

      val resultF = auditing.onServerError(header, new PlayException("", "", new NotFoundException("test")))
      await(resultF)
      mockConnector.recordedEvent shouldNot be(None)
      mockConnector.recordedEvent.get.auditType shouldBe ResourceNotFound
    }

    "send ServerValidationError event to DataStream for a JsValidationException that occurred in the microservice" in {
      val mockConnector = new MockAuditConnector()
      val auditing = new TestErrorAuditing(mockConnector)

      val resultF = auditing.onServerError(header, new PlayException("", "", new JsValidationException("GET", "", classOf[String], Seq.empty)))
      await(resultF)
      mockConnector.recordedEvent shouldNot be(None)
      mockConnector.recordedEvent.get.auditType shouldBe ServerValidationError
    }

    "chain onError call to parent" in {
      val mockConnector = new MockAuditConnector()
      val auditing = new TestErrorAuditing(mockConnector)

      val exception = new PlayException("", "", new NotFoundException("test"))
      val resultF = auditing.onServerError(header, exception)
      await(resultF)
      verify(mockSuperHandler).onServerError(header, exception)
    }

  }

  "in a case of the microservice endpoint not being found we" should {
    "send ResourceNotFound event to DataStream" in {

      val mockConnector = new MockAuditConnector()
      val auditing = new TestErrorAuditing(mockConnector)

      val resultF = auditing.onClientError(header, 404, "")
      await(resultF)
      mockConnector.recordedEvent shouldNot be(None)
      mockConnector.recordedEvent.get.auditType shouldBe EventTypes.ResourceNotFound
    }

    "chain onHandlerNotFound call to parent" in {
      val mockConnector = new MockAuditConnector()
      val auditing = new TestErrorAuditing(mockConnector)

      val resultF = auditing.onClientError(header, 404, "")
      await(resultF)
      verify(mockSuperHandler).onClientError(header, 404, "")
    }
  }

  "in a case of incorrect data being sent to the microservice endpoint we" should {

    "send ServerValidationError event to DataStream" in {
      val mockConnector = new MockAuditConnector()
      val auditing = new TestErrorAuditing(mockConnector)

      val resultF = auditing.onClientError(header, 400, "")
      await(resultF)
      mockConnector.recordedEvent shouldNot be(None)
      mockConnector.recordedEvent.get.auditType shouldBe EventTypes.ServerValidationError
    }

    "chain onBadRequest call to parent" in {
      val mockConnector = new MockAuditConnector()
      val auditing = new TestErrorAuditing(mockConnector)

      val resultF = auditing.onClientError(header, 400, "")
      await(resultF)
      verify(mockSuperHandler).onClientError(header, 400, "")
    }
  }
}

package com.intuit.karate.gatling

import java.util.Collections
import java.util.function.Consumer

import akka.actor.ActorSystem
import com.intuit.karate.core._
import com.intuit.karate.http.HttpRequest
import com.intuit.karate.{PerfHook, Runner}
import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.util.Clock
import io.gatling.core.action.{Action, ExitableAction}
import io.gatling.core.session.Session
import io.gatling.core.stats.StatsEngine

import scala.collection.JavaConverters._
import scala.concurrent.duration.{Duration, MILLISECONDS}
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

class KarateAction(val name: String, val tags: Seq[String], val protocol: KarateProtocol, val system: ActorSystem,
                   val statsEngine: StatsEngine, val clock: Clock, val next: Action) extends ExitableAction {

  def pause(time: Int) = {
    val duration = Duration(time, MILLISECONDS)
    try {
      Await.result(Future.never, duration)
    } catch {
      // we do all this to achieve a non-blocking "pause"
      // and the timeout exception will ALWAYS be thrown
      case e: Throwable => // do nothing
    }
  }

  override def execute(session: Session) = {

    implicit val executor: ExecutionContextExecutor = system.dispatcher

    val perfHook = new PerfHook {

      override def getPerfEventName(req: HttpRequest, sr: ScenarioRuntime): String = {
        val customName = protocol.nameResolver.apply(req, sr)
        val finalName = if (customName != null) customName else protocol.defaultNameResolver.apply(req, sr)
        val pauseTime = protocol.pauseFor(finalName, req.getMethod)
        if (pauseTime > 0) pause(pauseTime)
        return if (customName != null) customName else req.getMethod + " " + finalName
      }

      override def reportPerfEvent(event: PerfEvent): Unit = {
        val okOrNot = if (event.isFailed) KO else OK
        val message = if (event.getMessage == null) None else Option(event.getMessage)
        statsEngine.logResponse(session.scenario, List.empty, event.getName, event.getStartTime, event.getEndTime, okOrNot, Option(event.getStatusCode + ""), message)
      }

      override def submit(r: Runnable): Unit = Future {
        r.run()
      }

      override def afterFeature(vars: java.util.Map[String, Object]): Unit = {
        val map = if (vars == null) Map.empty else vars.asScala
        vars.remove("__gatling")
        next ! session.setAll(map)
      }
    }

    val pauseFunction: Consumer[java.lang.Number] = t => pause(t.intValue())
    val attribs: Object = (session.attributes + ("userId" -> session.userId) + ("pause" -> pauseFunction))
      .asInstanceOf[Map[String, AnyRef]].asJava
    val arg = Collections.singletonMap("__gatling", attribs)
    Runner.callAsync(name, tags.asJava, arg, perfHook)

  }

}

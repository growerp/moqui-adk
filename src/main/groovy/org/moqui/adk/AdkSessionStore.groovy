/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.adk

import com.google.adk.events.Event
import com.google.adk.sessions.BaseSessionService
import com.google.adk.sessions.GetSessionConfig
import com.google.adk.sessions.ListEventsResponse
import com.google.adk.sessions.ListSessionsResponse
import com.google.adk.sessions.Session
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import java.sql.Timestamp
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// Moqui-backed implementation of BaseSessionService for Google ADK.
// Stores sessions and events in AdkSession / AdkSessionEvent entities so
// conversations survive restarts and are auditable.
class AdkSessionStore implements BaseSessionService {
    protected final static Logger logger = LoggerFactory.getLogger(AdkSessionStore.class)

    private final ExecutionContextFactoryImpl ecf
    private final JsonSlurper jsonSlurper = new JsonSlurper()

    AdkSessionStore(ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf
    }

    @Override
    Single<Session> createSession(String appName, String userId, ConcurrentMap<String, Object> state, String sessionId) {
        def ec = ecf.getExecutionContext()
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.adk.AdkSession")
                    .set("sessionId", sessionId)
                    .set("appName", appName)
                    .set("userId", userId)
                    .set("state", state ? JsonOutput.toJson(new HashMap<>(state)) : "{}")
                    .set("createdDate", new Timestamp(System.currentTimeMillis()))
                    .store()

            ConcurrentMap<String, Object> sessionState = new ConcurrentHashMap<>(state ?: [:])
            return Single.just(Session.builder()
                    .id(sessionId)
                    .appName(appName)
                    .userId(userId)
                    .state(sessionState)
                    .build())
        } catch (Exception e) {
            logger.error("createSession failed for ${sessionId}", e)
            return Single.error(e)
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
            ec.destroy()
        }
    }

    @Override
    Maybe<Session> getSession(String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
        def ec = ecf.getExecutionContext()
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            def row = ec.entity.find("moqui.adk.AdkSession")
                    .condition("sessionId", sessionId)
                    .condition("appName", appName)
                    .condition("userId", userId)
                    .one()

            if (!row) return Maybe.empty()

            ConcurrentMap<String, Object> state = new ConcurrentHashMap<>(
                    row.state ? jsonSlurper.parseText(row.state as String) as Map : [:])

            return Maybe.just(Session.builder()
                    .id(sessionId)
                    .appName(appName)
                    .userId(userId)
                    .state(state)
                    .build())
        } catch (Exception e) {
            logger.error("getSession failed for ${sessionId}", e)
            return Maybe.error(e)
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
            ec.destroy()
        }
    }

    @Override
    Single<ListSessionsResponse> listSessions(String appName, String userId) {
        def ec = ecf.getExecutionContext()
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            def rows = ec.entity.find("moqui.adk.AdkSession")
                    .condition("appName", appName)
                    .condition("userId", userId)
                    .list()

            List<Session> sessions = rows.collect { row ->
                Session.builder()
                        .id(row.sessionId as String)
                        .appName(appName)
                        .userId(userId)
                        .state(new ConcurrentHashMap<>())
                        .build()
            }
            return Single.just(ListSessionsResponse.builder().sessions(sessions).build())
        } catch (Exception e) {
            return Single.error(e)
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
            ec.destroy()
        }
    }

    @Override
    Completable deleteSession(String appName, String userId, String sessionId) {
        def ec = ecf.getExecutionContext()
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find("moqui.adk.AdkSessionEvent").condition("sessionId", sessionId).deleteAll()
            ec.entity.find("moqui.adk.AdkSession").condition("sessionId", sessionId).deleteAll()
            return Completable.complete()
        } catch (Exception e) {
            return Completable.error(e)
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
            ec.destroy()
        }
    }

    @Override
    Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        // Return empty list — full event deserialization is complex.
        // ADK rebuilds its context from stored events only if needed by the runner.
        return Single.just(ListEventsResponse.builder().events([]).build())
    }

    @Override
    Single<Event> appendEvent(Session session, Event event) {
        def ec = ecf.getExecutionContext()
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.adk.AdkSessionEvent")
                    .set("eventId", UUID.randomUUID().toString())
                    .set("sessionId", session.id())
                    .set("eventJson", JsonOutput.toJson(event))
                    .set("eventDate", new Timestamp(System.currentTimeMillis()))
                    .create()
            return Single.just(event)
        } catch (Exception e) {
            logger.error("appendEvent failed for session ${session.id()}", e)
            return Single.error(e)
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
            ec.destroy()
        }
    }
}

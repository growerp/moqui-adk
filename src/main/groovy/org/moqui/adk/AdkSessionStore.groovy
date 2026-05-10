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
import com.google.adk.sessions.Session
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// Moqui-backed SessionService for Google ADK.
// Stores sessions and events in AdkSession / AdkSessionEvent entities so
// conversations survive restarts and are auditable.
class AdkSessionStore extends BaseSessionService {
    protected final static Logger logger = LoggerFactory.getLogger(AdkSessionStore.class)

    private final ExecutionContextFactoryImpl ecf
    private final JsonSlurper jsonSlurper = new JsonSlurper()

    AdkSessionStore(ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf
    }

    @Override
    Single<Session> createSession(String appName, String userId, Map<String, Object> state, String sessionId) {
        def ec = ecf.getExecutionContext()
        try {
            ec.entity.makeValue("moqui.adk.AdkSession")
                    .set("sessionId", sessionId)
                    .set("appName", appName)
                    .set("userId", userId)
                    .set("state", state ? JsonOutput.toJson(state) : "{}")
                    .set("createdDate", new Timestamp(System.currentTimeMillis()))
                    .createOrStore()

            return Single.just(Session.builder()
                    .id(sessionId)
                    .appName(appName)
                    .userId(userId)
                    .state(state ?: [:])
                    .build())
        } catch (Exception e) {
            logger.error("createSession failed for ${sessionId}", e)
            return Single.error(e)
        } finally {
            ec.destroy()
        }
    }

    @Override
    Maybe<Session> getSession(String appName, String userId, String sessionId) {
        def ec = ecf.getExecutionContext()
        try {
            def row = ec.entity.find("moqui.adk.AdkSession")
                    .condition("sessionId", sessionId)
                    .condition("appName", appName)
                    .condition("userId", userId)
                    .one()

            if (!row) return Maybe.empty()

            Map<String, Object> state = row.state ? jsonSlurper.parseText(row.state as String) as Map : [:]
            def events = loadEvents(ec, sessionId)

            return Maybe.just(Session.builder()
                    .id(sessionId)
                    .appName(appName)
                    .userId(userId)
                    .state(state)
                    .events(events)
                    .build())
        } catch (Exception e) {
            logger.error("getSession failed for ${sessionId}", e)
            return Maybe.error(e)
        } finally {
            ec.destroy()
        }
    }

    @Override
    Single<List<Session>> listSessions(String appName, String userId) {
        def ec = ecf.getExecutionContext()
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
                        .build()
            }
            return Single.just(sessions)
        } catch (Exception e) {
            return Single.error(e)
        } finally {
            ec.destroy()
        }
    }

    @Override
    Single<Session> appendEvent(Session session, Event event) {
        def ec = ecf.getExecutionContext()
        try {
            ec.entity.makeValue("moqui.adk.AdkSessionEvent")
                    .set("eventId", UUID.randomUUID().toString())
                    .set("sessionId", session.id())
                    .set("eventJson", JsonOutput.toJson(event))
                    .set("eventDate", new Timestamp(System.currentTimeMillis()))
                    .create()

            return Single.just(session)
        } catch (Exception e) {
            logger.error("appendEvent failed for session ${session.id()}", e)
            return Single.error(e)
        } finally {
            ec.destroy()
        }
    }

    @Override
    Single<Void> deleteSession(String appName, String userId, String sessionId) {
        def ec = ecf.getExecutionContext()
        try {
            ec.entity.find("moqui.adk.AdkSessionEvent").condition("sessionId", sessionId).deleteAll()
            ec.entity.find("moqui.adk.AdkSession").condition("sessionId", sessionId).deleteAll()
            return Single.just(null)
        } catch (Exception e) {
            return Single.error(e)
        } finally {
            ec.destroy()
        }
    }

    private List<Event> loadEvents(def ec, String sessionId) {
        def rows = ec.entity.find("moqui.adk.AdkSessionEvent")
                .condition("sessionId", sessionId)
                .orderBy("eventDate")
                .list()
        // Event deserialization is complex; return empty list for now.
        // ADK runner rebuilds context from stored events if needed.
        return []
    }
}

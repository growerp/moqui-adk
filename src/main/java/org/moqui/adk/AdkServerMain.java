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
package org.moqui.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.Gemini;
import com.google.adk.web.AdkWebServer;

/** Standalone launcher for the Google ADK web server.
 *  Run via: ./gradlew :runtime:component:moqui-adk:adkServer */
public class AdkServerMain {
    public static void main(String[] args) {
        String apiKey      = env("GOOGLE_GENAI_API_KEY", "adk.apiKey",      "");
        String modelName   = env("ADK_MODEL",            "adk.model",       "gemini-2.0-flash");
        String agentName   = env("ADK_AGENT_NAME",       "adk.agentName",   "MoquiAgent");
        String instruction = env("ADK_INSTRUCTION",      "adk.instruction",
                "You are a helpful assistant for the GrowERP ERP system.");

        Gemini model = Gemini.builder().modelName(modelName).apiKey(apiKey).build();
        LlmAgent agent = LlmAgent.builder()
                .name(agentName)
                .model(model)
                .instruction(instruction)
                .build();

        AdkWebServer.start(agent);
    }

    private static String env(String envVar, String sysProp, String defaultVal) {
        String val = System.getProperty(sysProp);
        if (val != null && !val.isEmpty()) return val;
        val = System.getenv(envVar);
        return (val != null && !val.isEmpty()) ? val : defaultVal;
    }
}

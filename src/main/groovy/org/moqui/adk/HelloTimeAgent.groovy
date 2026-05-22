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

import com.google.adk.agents.BaseAgent
import com.google.adk.agents.LlmAgent
import com.google.adk.tools.Annotations.Schema
import com.google.adk.tools.FunctionTool

/**
 * Example agent: reports the current time for a requested city.
 * Ported from the ADK Java quickstart sample (HelloTimeAgent).
 */
class HelloTimeAgent {

    static final BaseAgent ROOT_AGENT = initAgent()

    private static BaseAgent initAgent() {
        LlmAgent.builder()
            .name('hello-time-agent')
            .description('Tells the current time in a specified city')
            .instruction('''\
You are a helpful assistant that tells the current time in a city.
Use the 'getCurrentTime' tool for this purpose.
''')
            .model('gemini-2.0-flash')
            .tools(FunctionTool.create(HelloTimeAgent.class, 'getCurrentTime'))
            .build()
    }

    @Schema(description = 'Get the current time for a given city')
    static Map<String, String> getCurrentTime(
            @Schema(name = 'city', description = 'Name of the city to get the time for') String city) {
        // Stub: real implementation would look up local time via timezone API
        String time = java.time.ZonedDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern('hh:mm a z'))
        return [city: city, currentTime: time]
    }
}

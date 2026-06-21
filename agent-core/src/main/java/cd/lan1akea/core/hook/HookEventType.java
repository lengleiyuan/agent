/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cd.lan1akea.core.hook;


public enum HookEventType {

    /** Before agent starts processing */
    PRE_CALL,

    /** After agent completes processing */
    POST_CALL,

    /** Before LLM reasoning */
    PRE_REASONING,

    /** After LLM reasoning completes */
    POST_REASONING,

    /** During LLM reasoning streaming */
    REASONING_BLOCK,

    /** Before tool execution */
    PRE_TOOL,

    /** After tool execution completes */
    POST_TOOL,

    /** During tool execution streaming */
    TOOL_BLOCK,

    /** Before summary generation (when max iterations reached) */
    PRE_SUMMARY,

    /** After summary generation completes */
    POST_SUMMARY,

    /** During summary streaming */
    SUMMARY_BLOCK,

    /** When an error occurs */
    ERROR
}

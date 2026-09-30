package org.omarchy.flux.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.omarchy.flux.protocol.INCOMING
import org.omarchy.flux.protocol.OUTGOING
import org.omarchy.flux.protocol.Types
import org.omarchy.flux.protocol.json

class OpenChamberTest {
    @Test
    fun capabilityListsCarryTheType() {
        assertTrue(Types.FLUX_OPENCHAMBER in INCOMING)
        assertTrue(Types.FLUX_OPENCHAMBER in OUTGOING)
    }

    @Test
    fun parseState() {
        val body = json.parseToJsonElement(
            """
            {"kind":"state","enabled":true,"running":true,"control":true,
             "agents":[
               {"id":"ses_1","title":"Fix build","agent":"build","status":"blocked","project":"flux","model":"m1","waiting":"form"},
               {"id":"","title":"no id"},
               {"title":"no id either"}],
             "kinds":[{"id":"build","name":"Build"},{"id":"plan"},{"id":""}],
             "dirs":["~","~/Code/app"]}
            """.trimIndent(),
        ) as JsonObject
        val state = parseOpenChamberState(body)!!
        assertTrue(state.enabled && state.running && state.control)
        assertEquals(1, state.sessions.size)
        val s = state.sessions[0]
        assertEquals("ses_1", s.id)
        assertEquals(AgentStatus.Blocked, s.status)
        assertEquals("flux", s.project)
        assertEquals("form", s.waiting)
        assertEquals(listOf("build", "plan"), state.kinds.map { it.id })
        assertEquals("plan", state.kinds[1].name)
        assertEquals(listOf("~", "~/Code/app"), state.dirs)
        assertEquals(1, state.blocked)
    }

    @Test
    fun parseStateGatesKindsAndDirs() {
        val body = json.parseToJsonElement(
            """{"kind":"state","enabled":true,"running":true,"control":false,
                "agents":[],"kinds":[{"id":"build"}],"dirs":["~"]}""",
        ) as JsonObject
        val state = parseOpenChamberState(body)!!
        assertTrue(state.kinds.isEmpty() && state.dirs.isEmpty())
    }

    @Test
    fun parseStateWhenOff() {
        val body = json.parseToJsonElement("""{"kind":"state","enabled":false,"running":true,"agents":[]}""") as JsonObject
        val state = parseOpenChamberState(body)!!
        assertTrue(!state.enabled && !state.running && !state.control)
        assertNull(parseOpenChamberState(buildJsonObject { put("kind", "output") }))
    }

    @Test
    fun sortSessionsPutsBlockedFirst() {
        val sessions = listOf(
            OpenChamberSession("a", status = AgentStatus.Idle),
            OpenChamberSession("b", status = AgentStatus.Working),
            OpenChamberSession("c", status = AgentStatus.Blocked),
            OpenChamberSession("d", status = AgentStatus.Unknown),
        )
        assertEquals(listOf("c", "b", "a", "d"), sortSessions(sessions).map { it.id })
    }

    @Test
    fun parseOutputPlain() {
        val body = json.parseToJsonElement(
            """{"kind":"output","session":"ses_1","text":"hello","truncated":true,"pending":[]}""",
        ) as JsonObject
        val out = parseOpenChamberOutput(body)!!
        assertEquals("ses_1", out.session)
        assertEquals("hello", out.plain)
        assertTrue(!out.rich && out.truncated && out.entries.isEmpty())
        assertNull(parseOpenChamberOutput(buildJsonObject { put("kind", "state") }))
    }

    @Test
    fun parseOutputRich() {
        val entries = json.parseToJsonElement(
            """
            [{"r":"u","t":"fix the build"},{"r":"a","n":"build (m1)"},{"r":"a","t":"On it."},
             {"r":"r","t":"thinking"},{"r":"t","n":"shell","s":"completed","i":"go build","o":"ok"}]
            """.trimIndent(),
        ).toString()
        val body = buildJsonObject {
            put("kind", JsonPrimitive("output"))
            put("session", JsonPrimitive("ses_1"))
            put("format", JsonPrimitive("rich"))
            put("text", JsonPrimitive(entries))
        }
        val out = parseOpenChamberOutput(body)!!
        assertTrue(out.rich)
        assertEquals(5, out.entries.size)
        assertEquals(Entry.User("fix the build"), out.entries[0])
        assertEquals(Entry.Assistant("build (m1)"), out.entries[1])
        assertEquals(Entry.Text("On it."), out.entries[2])
        assertEquals(Entry.Reasoning("thinking"), out.entries[3])
        val tool = out.entries[4] as Entry.Tool
        assertEquals("shell", tool.name)
        assertEquals("completed", tool.status)
        assertEquals("go build", tool.input)
        assertEquals("ok", tool.output)
    }

    @Test
    fun parseOutputPending() {
        val body = json.parseToJsonElement(
            """
            {"kind":"output","session":"ses_1","text":"","pending":[
              {"kind":"form","id":"frm_1","title":"Questions","fields":[
                 {"key":"choice","type":"string","label":"Pick","required":true,
                  "options":[{"value":"1","label":"One"},{"value":"2"}]}]},
              {"kind":"permission","id":"perm_1","action":"shell","resources":["ls"]},
              {"kind":"other","id":"x"}]}
            """.trimIndent(),
        ) as JsonObject
        val out = parseOpenChamberOutput(body)!!
        assertEquals(2, out.pending.size)
        val form = out.pending[0] as Pending.Form
        assertEquals("frm_1", form.id)
        assertEquals(1, form.fields.size)
        assertEquals(FormOption("2", "2"), form.fields[0].options[1])
        val permission = out.pending[1] as Pending.Permission
        assertEquals("shell", permission.action)
        assertEquals(listOf("ls"), permission.resources)
    }

    @Test
    fun parseSentAndDone() {
        assertTrue(parseOpenChamberSent(json.parseToJsonElement("""{"kind":"sent","session":"ses_1","action":"prompt"}""") as JsonObject) != null)
        assertNull(parseOpenChamberSent(json.parseToJsonElement("""{"kind":"sent"}""") as JsonObject))
        assertEquals("create", parseOpenChamberDone(json.parseToJsonElement("""{"kind":"created","session":"ses_2"}""") as JsonObject)?.action)
        assertEquals("close", parseOpenChamberDone(json.parseToJsonElement("""{"kind":"closed","session":"ses_2"}""") as JsonObject)?.action)
        assertNull(parseOpenChamberDone(json.parseToJsonElement("""{"kind":"sent"}""") as JsonObject))
    }

    @Test
    fun formAnswerBuildsTheValues() {
        val fields = listOf(
            FormField("choice", "string", options = listOf(FormOption("2", "Two"))),
            FormField("note", "string"),
            FormField("count", "integer"),
            FormField("ok", "boolean"),
            FormField("ack", "external"),
        )
        val answer = formAnswer(fields, mapOf("choice" to "2", "note" to "hello", "count" to "3", "ok" to "true"))
        assertEquals("2", answer["choice"]?.jsonPrimitive())
        assertEquals("hello", answer["note"]?.jsonPrimitive())
        assertEquals("3", answer["count"]?.jsonPrimitive())
        assertEquals("true", answer["ok"]?.jsonPrimitive())
        assertEquals("true", answer["ack"]?.jsonPrimitive())
        // A field that the user left empty is left out.
        assertNull(formAnswer(fields, mapOf("choice" to "2"))["note"])
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitive() = (this as JsonPrimitive).content

    @Test
    fun trackerFindsNeedsInputAndFinish() {
        val tracker = OpenChamberTracker()
        val working = OpenChamberSession("a", status = AgentStatus.Working)
        val blocked = OpenChamberSession("a", status = AgentStatus.Blocked)
        val idle = OpenChamberSession("a", status = AgentStatus.Idle)

        // A working session on the first list clears an old notification.
        assertEquals(listOf(SessionAlert.Clear("a")), tracker.update(listOf(working)))

        val needs = tracker.update(listOf(blocked))
        assertEquals(1, needs.size)
        assertTrue(needs[0] is SessionAlert.NeedsInput)

        // The same status again posts nothing.
        assertTrue(tracker.update(listOf(blocked)).isEmpty())

        // A blocked session that becomes idle is not a finish; it clears.
        assertEquals(listOf(SessionAlert.Clear("a")), tracker.update(listOf(idle)))

        // A session that works and then stops is a finish.
        tracker.update(listOf(working))
        assertEquals(listOf(SessionAlert.Finished(idle)), tracker.update(listOf(idle)))

        // A gone session is cleared.
        assertEquals(listOf(SessionAlert.Clear("a")), tracker.update(emptyList()))

        // After a reconnect, the first list is the baseline again.
        tracker.restart()
        assertTrue(tracker.update(listOf(blocked)).isEmpty())
    }
}

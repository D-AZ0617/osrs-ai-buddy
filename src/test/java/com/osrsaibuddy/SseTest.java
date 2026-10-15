package com.osrsaibuddy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Reading server-sent events: what both providers stream their replies in. */
public class SseTest
{
	private static List<String> read(String stream) throws IOException
	{
		List<String> events = new ArrayList<>();
		Sse.read(new Buffer().writeUtf8(stream), (event, data) ->
		{
			events.add(event + "|" + data);
			return true;
		});
		return events;
	}

	@Test
	public void eventsAreSplitAtBlankLines() throws IOException
	{
		assertEquals(List.of("message_start|{\"a\":1}", "ping|{}", "message|second"),
			read("event: message_start\ndata: {\"a\":1}\n\nevent: ping\ndata: {}\n\ndata: second\n\n"));
	}

	@Test
	public void dataOnSeveralLinesIsJoined() throws IOException
	{
		assertEquals(List.of("message|one\ntwo\n three"), read("data: one\ndata:two\ndata:  three\n\n"));
	}

	@Test
	public void commentsAndOtherFieldsAreSkipped() throws IOException
	{
		assertEquals(List.of("message|x"), read(": keep-alive\n\nid: 7\nretry: 1000\n: note\ndata: x\n\n"));
		assertEquals("an event without data isn't one", List.of(), read("event: ping\n\n"));
	}

	@Test
	public void windowsLineEndingsAndAByteOrderMarkAreFine() throws IOException
	{
		assertEquals(List.of("e|a", "message|b"), read("﻿event: e\r\ndata: a\r\n\r\ndata: b\r\n\r\n"));
	}

	@Test
	public void anEventCutOffByTheEndIsDropped() throws IOException
	{
		assertEquals(List.of("message|whole"), read("data: whole\n\ndata: {\"half\":"));
		assertEquals(List.of("message|whole"), read("data: whole\n\ndata: not followed by a blank line\n"));
	}

	@Test
	public void theHandlerCanStopReading() throws IOException
	{
		List<String> seen = new ArrayList<>();
		Buffer source = new Buffer().writeUtf8("data: a\n\ndata: [DONE]\n\ndata: after\n\n");
		Sse.read(source, (event, data) ->
		{
			seen.add(data);
			return !Sse.DONE.equals(data);
		});
		assertEquals(List.of("a", "[DONE]"), seen);
		assertEquals("the rest is left unread", "data: after\n\n", source.readUtf8());
	}

	@Test
	public void onlyEventStreamsAreReadAsOne()
	{
		assertTrue(Sse.isEventStream(response("text/event-stream; charset=utf-8")));
		assertTrue(Sse.isEventStream(response("Text/Event-Stream")));
		assertFalse(Sse.isEventStream(response("application/json")));
		assertFalse(Sse.isEventStream(response(null)));
	}

	private static Response response(String type)
	{
		Response.Builder b = new Response.Builder()
			.request(new Request.Builder().url("http://127.0.0.1/").build())
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("OK");
		if (type != null)
		{
			b.header("Content-Type", type);
		}
		return b.build();
	}
}


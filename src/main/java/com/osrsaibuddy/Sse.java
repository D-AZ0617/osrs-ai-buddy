package com.osrsaibuddy;

import java.io.IOException;
import java.util.Locale;
import okhttp3.Response;
import okio.BufferedSource;

/**
 * Server-sent events, the format both providers stream replies in: "event:" and "data:" lines, and a blank line after
 * each event. Read straight from the response as it arrives, on OkHttp's thread (that's what its callbacks are for).
 */
final class Sse
{
	/** What OpenAI-style streams send as their last event's data. */
	static final String DONE = "[DONE]";

	private Sse()
	{
	}

	interface Handler
	{
		/**
		 * One event: its name ("message" when it has none) and its data, the lines of a multi-line data joined with line
		 * breaks. Returns false when nothing more is wanted from the stream.
		 */
		boolean onEvent(String event, String data);
	}

	/** Whether the answer is a stream of events, rather than one JSON answer: some services ignore "stream". */
	static boolean isEventStream(Response response)
	{
		String type = response.header("Content-Type");
		return type != null && type.trim().toLowerCase(Locale.ROOT).startsWith("text/event-stream");
	}

	/** Some plan responses omit Content-Type. Peek at framing without consuming the first event. */
	static boolean hasEventFraming(Response response) throws IOException
	{
		if (response.body() == null) { return false; }
		BufferedSource peek = response.body().source().peek();
		peek.request(16);
		String prefix = peek.buffer().clone().readUtf8(Math.min(16, peek.buffer().size()));
		if (prefix.startsWith("\uFEFF")) { prefix = prefix.substring(1); }
		return prefix.startsWith("event:") || prefix.startsWith("data:") || prefix.startsWith(":")
			|| prefix.startsWith("id:") || prefix.startsWith("retry:");
	}

	/**
	 * Reads events until the handler has had enough or the stream ends. An event the end cuts off (no blank line after
	 * it) isn't passed on: it may be missing lines. Comments (":" lines, often sent to keep the connection open) and the
	 * fields for reconnecting ("id", "retry") are skipped; a reply can't pick up where it left off anyway.
	 */
	static void read(BufferedSource source, Handler handler) throws IOException
	{
		String event = null;
		StringBuilder data = null;
		boolean first = true;
		String line;
		while ((line = source.readUtf8Line()) != null)
		{
			if (first && line.startsWith("﻿"))
			{
				line = line.substring(1);
			}
			first = false;
			if (line.isEmpty())
			{
				if (data != null && data.length() > 0 && !handler.onEvent(event == null || event.isEmpty() ? "message" : event, data.toString()))
				{
					return;
				}
				event = null;
				data = null;
				continue;
			}
			if (line.startsWith(":"))
			{
				continue;
			}
			int colon = line.indexOf(':');
			String field = colon < 0 ? line : line.substring(0, colon);
			String value = colon < 0 ? "" : line.substring(colon + 1);
			if (value.startsWith(" "))
			{
				value = value.substring(1);
			}
			if (field.equals("data"))
			{
				if (data == null)
				{
					data = new StringBuilder();
				}
				else
				{
					data.append('\n');
				}
				data.append(value);
			}
			else if (field.equals("event"))
			{
				event = value;
			}
		}
	}
}

package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The provider's list of models, asked for with the player's key: by "Test connection", which says what it means for
 * their setup, and quietly for the model picker next to Send. The result is kept for the provider, address and key it
 * was made with, and read against whichever model is set now, so choosing another model (in the panel or in the
 * settings) updates it without asking again.
 */
final class ConnectionCheck
{
    static final String NO_MODEL_LIST = "This service doesn't list its models; check the model name on its website.";
	/** Model ids that aren't for chatting: OpenAI's embeddings, speech, images, moderation and the like. */
	private static final String[] NOT_CHAT = {"embed", "tts", "whisper", "dall-e", "moderation", "image", "audio",
		"transcribe", "realtime", "search"};

	enum Kind
	{
		/** Still asking. */
		TESTING,
		OK,
		/** Connected, but the model needs a look. */
		WARNING,
		ERROR
	}

	/** What the panel's banner says after a Test. */
	static final class Note
	{
		final Kind kind;
		final String text;

		Note(Kind kind, String text)
		{
			this.kind = kind;
			this.text = text;
		}
	}

	/** Which provider, address and key the check was for (see {@link #setupKey}). */
	final String setup;
	/** The chat models listed, or null while asking, after an error, or when the service has no list. */
	final List<String> models;
	/** Why it failed, or null. */
	final String error;

	private ConnectionCheck(String setup, List<String> models, String error)
	{
		this.setup = setup;
		this.models = models;
		this.error = error;
	}

	static ConnectionCheck testing(String setup)
	{
		return new ConnectionCheck(setup, null, null);
	}

	/** {@code sorted}: put them in alphabetical order (Anthropic's list comes newest first, which is better kept). */
	static ConnectionCheck listed(String setup, List<String> ids, boolean sorted)
	{
		return new ConnectionCheck(setup, chatModels(ids, sorted), null);
	}

	static ConnectionCheck failed(String setup, String error)
	{
		return new ConnectionCheck(setup, null, error);
	}

	/**
	 * Tells setups apart without keeping the key itself: a different provider, address or key makes an earlier check
	 * say nothing about this one.
	 */
	static String setupKey(String provider, String url, String key)
	{
		return provider + "\u0000" + (url == null ? "" : url.trim()) + "\u0000" + (key == null ? 0 : key.hashCode());
	}

	/** The ids worth offering as chat models, without duplicates. */
	static List<String> chatModels(List<String> ids, boolean sorted)
	{
		List<String> models = new ArrayList<>();
		for (String id : ids)
		{
			if (id != null && !id.trim().isEmpty() && !models.contains(id) && isChatModel(id))
			{
				models.add(id);
			}
		}
		if (sorted)
		{
			models.sort(String.CASE_INSENSITIVE_ORDER);
		}
		return models;
	}

	private static boolean isChatModel(String id)
	{
		String lower = id.toLowerCase(Locale.ROOT);
		for (String word : NOT_CHAT)
		{
			if (lower.contains(word))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * What the check says about {@code model} (blank when none is set yet). {@code service}: "Anthropic", "OpenAI", or
	 * the address of a compatible service; {@code keyed}: the player's own key decides which models they get (Claude,
	 * ChatGPT); {@code keyUnchecked}: a key went to a service whose list may not need one, so the list says nothing
	 * about it.
	 */
	Note note(String service, String model, boolean keyed, boolean keyUnchecked)
	{
		if (error != null)
		{
			if (ConnectionCheck.NO_MODEL_LIST.equals(error))
			{
				// Any web server answers "not found", so this says little: not even that the URL is right. With no model
				// set, there's none to pick either: say where its name goes.
				if (model == null || model.trim().isEmpty())
				{
					return new Note(Kind.WARNING, service + " answered, but not with a list of models, so Test can't "
						+ "check the URL or offer a model. Type the model's name next to Send, as the service's website "
						+ "gives it.");
				}
				return new Note(Kind.WARNING, service + " answered, but not with a list of models, so Test can't check "
					+ "the URL or the model name. Check both on the service's website.");
			}
			return new Note(Kind.ERROR, error);
		}
		if (models == null)
		{
			return new Note(Kind.TESTING, "Testing the connection\u2026");
		}
		if (models.isEmpty())
		{
			return new Note(Kind.WARNING, "Connected to " + service + ", but it listed no models to chat with.");
		}
		// OpenRouter lists its models for anyone: a wrong key there only shows when a message is sent.
		String key = keyUnchecked ? " Your key is checked when you send: some services list their models for anyone." : "";
		if (model == null || model.trim().isEmpty())
		{
			return new Note(Kind.OK, "Connected to " + service + "." + key + " Pick a model next to Send.");
		}
		if (has(models, model))
		{
			return new Note(Kind.OK, "Connected to " + service + ". " + model + " is available." + key);
		}
		return new Note(Kind.WARNING, (keyed ? "Connected, but " + model + " isn't in the list your account can use."
			: "Connected to " + service + ", but " + model + " isn't one of its models.") + " Pick another next to Send.");
	}

	/** Whether {@code model} is one of {@code models}, as named there (see {@link #sameModel}). */
	private static boolean has(List<String> models, String model)
	{
		for (String id : models)
		{
			if (sameModel(id, model))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether {@code listed}, a model as the provider's list names it, is {@code model} as the player set it: Ollama
	 * lists "llama3.2:latest" for the model asked for as "llama3.2", and Anthropic lists an alias such as
	 * "claude-haiku-4-5" as the dated model it points to, "claude-haiku-4-5-20251001".
	 */
	static boolean sameModel(String listed, String model)
	{
		if (listed.equals(model) || !model.contains(":") && listed.equals(model + ":latest"))
		{
			return true;
		}
		return listed.length() == model.length() + 9 && listed.startsWith(model + "-")
			&& listed.substring(model.length() + 1).matches("\\d{8}");
	}

	/**
	 * What the model picker offers: the model that's set (blank: none), always, and the chat models {@code listed}, in
	 * their order. The model that's set takes the place of the one it is in the list, under the name it's set as (so
	 * "claude-haiku-4-5" isn't offered twice, once by its dated name), or goes first when the list hasn't got it.
	 */
	static List<String> choices(String model, List<String> listed)
	{
		String current = model == null ? "" : model.trim();
		List<String> out = new ArrayList<>();
		boolean placed = current.isEmpty();
		for (String id : listed == null ? Collections.<String>emptyList() : listed)
		{
			if (current.isEmpty() || !sameModel(id, current))
			{
				out.add(id);
			}
			else if (!placed)
			{
				out.add(current);
				placed = true;
			}
		}
		if (!placed)
		{
			out.add(0, current);
		}
		return out;
	}

	/**
	 * The model picker's tooltip: the model that's set, in full (a long one doesn't fit next to Send), how to choose,
	 * and why the list is short when it is (see {@link #listNote}). Plain sentences of our own, starting with our own
	 * words, so they can never be read as HTML.
	 */
	static String pickerTip(ConnectionCheck check, String problem, String service, String model)
	{
		String m = model == null ? "" : model.trim();
		String how = m.isEmpty()
			? "The model new messages go to. Click to pick one, or to type its name."
			: "New messages go to " + m + ". Click to pick another, or to type its name.";
		String note = listNote(check, problem, service);
		return note == null ? how : how + " " + note;
	}

	/**
	 * Why the model picker's list is short, for its tooltip and its menu, or null when it isn't. {@code check}: the
	 * latest list asked for this setup, or null; {@code problem}: why the provider can't be reached (AI requests off
	 * included), or null.
	 */
	static String listNote(ConnectionCheck check, String problem, String service)
	{
		if (problem != null || check == null)
		{
			return "The list fills in once AI requests are on and the provider is set up.";
		}
		if (check.error != null)
		{
			return ConnectionCheck.NO_MODEL_LIST.equals(check.error)
				? service + " doesn't list its models: type the name its website gives."
				: "Couldn't list the models: " + check.error;
		}
		if (check.models == null)
		{
			return "Looking up the models you can use\u2026";
		}
		return check.models.isEmpty() ? service + " listed no models to chat with." : null;
	}
}


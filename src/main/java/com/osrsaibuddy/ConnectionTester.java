package com.osrsaibuddy;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Asks the provider which models the key can use, and keeps the latest answer for the provider, address and key it was
 * asked with (see {@link ConnectionCheck}): for "Test connection", which the panel's banner reports on, and quietly for
 * the model picker. Runs on the Swing EDT; the answer comes back through {@code edt}, and counts only if it's for the
 * latest request and that wasn't stopped since.
 */
final class ConnectionTester
{
	/** Runs a task on the EDT later, never right away: SwingUtilities::invokeLater. */
	private final Executor edt;
	/** The result is in: show it. */
	private final Runnable changed;
	/** The latest list asked for, or null. */
	private ConnectionCheck check;
	/** The request in flight, or null. */
	private ChatApi.Pending testing;
	/** The player asked for the latest list with Test, and hasn't closed what the banner says about it. */
	private boolean shown;

	ConnectionTester(Executor edt, Runnable changed)
	{
		this.edt = edt;
		this.changed = changed;
	}

	/**
	 * "Test connection": asks {@code api} for its models, instead of anything still asking, and has the banner say what
	 * the answer means. {@code setup}: the provider, address and key it's for ({@link ProviderSetup#connection});
	 * {@code sorted}: put the models in alphabetical order.
	 */
	void start(ChatApi api, String setup, boolean sorted)
	{
		ask(api, setup, sorted);
		shown = true;
	}

	/**
	 * For the model picker: asks for the models quietly, once for each provider, address and key, until a Test or a
	 * change of setup asks again. Never while {@code problem} says the provider can't be reached (that includes AI
	 * requests being off): then {@code api} isn't even made. Returns whether it asked.
	 */
	boolean list(String problem, String setup, Supplier<ChatApi> api, boolean sorted)
	{
		return (check == null || !check.setup.equals(setup)) && refresh(problem, setup, api, sorted);
	}

	/**
	 * "Refresh list" in the model picker's menu: asks for the models again, quietly, even when the list for this setup
	 * is in, instead of anything still asking. As with {@link #list}, never while {@code problem} says the provider
	 * can't be reached, and not while a Test for this setup is still asking: its answer is the list too, and the
	 * banner is waiting for it. Returns whether it asked.
	 */
	boolean refresh(String problem, String setup, Supplier<ChatApi> api, boolean sorted)
	{
		if (problem != null || shown && testing != null && check(setup) != null)
		{
			return false;
		}
		ChatApi made = api.get();
		if (made == null)
		{
			return false;
		}
		ask(made, setup, sorted);
		shown = false;
		return true;
	}

	private void ask(ChatApi api, String setup, boolean sorted)
	{
		stop();
		check = ConnectionCheck.testing(setup);
		// Set before any answer can be handled: answers are handled on this (the EDT) thread, after this method.
		ChatApi.Pending[] request = new ChatApi.Pending[1];
		try
		{
			request[0] = api.listModels(new ChatApi.ModelsListener()
			{
				@Override
				public void onModels(List<String> ids)
				{
					edt.execute(() -> tested(request[0], ConnectionCheck.listed(setup, ids, sorted)));
				}

				@Override
				public void onError(String message)
				{
					edt.execute(() -> tested(request[0], ConnectionCheck.failed(setup, message)));
				}
			});
		}
		catch (RuntimeException e)
		{
			// As with sending: the exception's message can contain the API key.
			check = ConnectionCheck.failed(setup, RequestRunner.COULDNT_SEND);
		}
		testing = request[0];
	}

	/** The answer to {@code request}: kept only if that's still the request in flight. */
	private void tested(ChatApi.Pending request, ConnectionCheck result)
	{
		if (request == null || testing != request || request.isCancelled())
		{
			return;
		}
		testing = null;
		check = result;
		changed.run();
	}

	/** Stops a request in flight, if any, and forgets the last result. */
	void stop()
	{
		if (testing != null)
		{
			testing.cancel();
			testing = null;
		}
		check = null;
		shown = false;
	}

	/** The player closed the banner: the list is kept for the picker. */
	void dismiss()
	{
		shown = false;
	}

	/**
	 * A message went. Good news from a Test has been read by then, and makes way for the conversation; a warning or an
	 * error stays until it's fixed or closed. Returns whether the banner changed. The arguments are {@link #note}'s.
	 */
	boolean sent(String setup, String service, String model, boolean keyed, boolean keyUnchecked)
	{
		ConnectionCheck.Note n = note(setup, service, model, keyed, keyUnchecked);
		if (n != null && n.kind == ConnectionCheck.Kind.OK)
		{
			shown = false;
			return true;
		}
		return false;
	}

	/**
	 * What the latest Test says about the setup as it is now, for the banner, or null when there's nothing to say: no
	 * Test, the player closed it, or it was for another provider, address or key ({@code setup}). A different model is
	 * fine: the list is read against it.
	 */
	ConnectionCheck.Note note(String setup, String service, String model, boolean keyed, boolean keyUnchecked)
	{
		ConnectionCheck c = check(setup);
		return shown && c != null ? c.note(service, model, keyed, keyUnchecked) : null;
	}

	/** The latest list asked for {@code setup}, by Test or the picker, or null. */
	ConnectionCheck check(String setup)
	{
		return check != null && check.setup.equals(setup) ? check : null;
	}
}

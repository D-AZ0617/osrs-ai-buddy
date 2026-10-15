package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Local public-client OAuth. Credentials never enter RuneLite configuration or profile sync. */
final class ChatGptAuth
{
	static final String ISSUER = "https://auth.openai.com";
	static final String RESOURCE = "https://api.openai.com/v1";
	static final String DIRECT = "chatgpt.tokens.use.direct";
	static final String DYNAMIC = "dynamic_agent_client";
	private final OkHttpClient http;
	private final Gson gson;
	private final RegistrationStore store;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "OSRS AI Buddy sign-in");
		thread.setDaemon(true);
		return thread;
	});
	private volatile JsonObject profile;
	private volatile HttpServer callback;
	private volatile boolean closed;
	private volatile boolean signingIn;
	private volatile long generation;
	private String host;
	

	ChatGptAuth(OkHttpClient http, Gson gson, RegistrationStore store)
	{
		this.http = http;
		this.gson = gson;
		this.store = store;
	}

	void load(Consumer<String> done)
	{
		worker.execute(() -> {
			try
			{
				JsonObject registration = registrationOnly(store.open());
				host = text(registration, "ext_agent_host_id");
				if (host.isEmpty()) { host = "urn:uuid:" + UUID.randomUUID(); }
				if (!host.matches("urn:uuid:[0-9a-fA-F-]{36}")) { throw new IOException("Invalid host ID"); }
				profile = registration.has("client_id") ? registration : null;
				persist(registration);
				done.accept(null);
			}
			catch (Exception e)
			{
				host = null;
				profile = null;
				done.accept("Couldn't open ChatGPT registration storage. Close any other RuneLite window using this profile, then restart OSRS AI Buddy.");
			}
		});
	}

	boolean connected()
	{
		JsonObject p = profile;
		return p != null && !text(p, "access_token").isEmpty() && hasPermission(p);
	}

	static boolean hasPermission(JsonObject p)
	{
		return Arrays.asList(text(p, "scope").split("\\s+")).contains(DIRECT);
	}

	String account()
	{
		JsonObject p = profile;
		return p == null ? "Not connected" : text(p, "email");
	}

	String connectionId()
	{
		JsonObject p = profile;
		return p == null ? "signed-out" : text(p, "client_id") + ":" + generation;
	}

	boolean signingIn() { return signingIn; }
	boolean signedIn() { return profile != null && !text(profile, "access_token").isEmpty(); }
	java.util.concurrent.Executor requestExecutor() { return worker; }

	void signIn(Consumer<String> browser, Consumer<String> done)
	{
		if (closed || signingIn) { return; }
		signingIn = true;
		worker.execute(() -> {
			HttpServer server = null;
			try
			{
				if (closed || host == null) { throw new IOException("Storage unavailable"); }
				JsonObject previous = profile;
				String client = previous == null ? DYNAMIC : text(previous, "client_id");
				if (client.isEmpty()) { client = DYNAMIC; }
				String selectedClient = client;
				String state = random();
				String nonce = random();
				String verifier = random();
				CompletableFuture<HttpUrl> result = new CompletableFuture<>();
				server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
				callback = server;
				String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/callback";
				server.createContext("/auth/callback", exchange -> {
					HttpUrl url = HttpUrl.parse("http://127.0.0.1" + exchange.getRequestURI());
					boolean valid = "GET".equals(exchange.getRequestMethod()) && url != null
						&& "/auth/callback".equals(url.encodedPath())
						&& url.queryParameterValues("state").size() == 1
						&& state.equals(url.queryParameter("state"));
					String message = valid ? "Sign-in received. You can return to RuneLite." : "Invalid sign-in callback.";
					byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
					exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
					exchange.getResponseHeaders().set("Cache-Control", "no-store");
					exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
					exchange.sendResponseHeaders(valid ? 200 : 400, bytes.length);
					try (java.io.OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
					if (valid) { result.complete(url); }
				});
				server.start();
				HttpUrl.Builder authorize = HttpUrl.get(ISSUER + "/api/accounts/authorize").newBuilder()
					.addQueryParameter("client_id", client).addQueryParameter("ext_agent_host_id", host)
					.addQueryParameter("response_type", "code").addQueryParameter("redirect_uri", redirect)
					.addQueryParameter("scope", "openid profile email offline_access resource.invoke " + DIRECT)
					.addQueryParameter("resource", RESOURCE).addQueryParameter("state", state)
					.addQueryParameter("nonce", nonce).addQueryParameter("code_challenge_method", "S256")
					.addQueryParameter("code_challenge", challenge(verifier));
				if (DYNAMIC.equals(client)) { authorize.addQueryParameter("agent_name_hint", "OSRS AI Buddy"); }
				browser.accept(authorize.build().toString());
				HttpUrl returned = result.get(3, TimeUnit.MINUTES);
				if (returned.queryParameter("error") != null) { throw new IOException("Authorization declined"); }
				String issued = issuedClient(selectedClient, returned.queryParameter("client_id"));
				String code = returned.queryParameter("code");
				if (code == null || code.isEmpty()) { throw new IOException("Missing code"); }
				// Retain registration even if this short-lived code cannot be exchanged.
				JsonObject registration = previous == null ? new JsonObject() : previous.deepCopy();
				registration.addProperty("client_id", issued);
				if (previous == null || text(previous, "access_token").isEmpty())
				{
					persist(registration);
					profile = registration;
				}
				JsonObject tokens = token(new FormBody.Builder().add("grant_type", "authorization_code")
					.add("client_id", issued).add("code", code).add("code_verifier", verifier)
					.add("redirect_uri", redirect).add("resource", RESOURCE).build());
				JsonObject jwks = get(ISSUER + "/.well-known/jwks.json");
				JsonObject identity = verifyIdentity(text(tokens, "id_token"), issued, nonce, jwks, gson);
				if (previous != null && !text(previous, "subject").isEmpty()
					&& !text(previous, "subject").equals(text(identity, "sub"))) { throw new IOException("Account mismatch"); }
				tokens.addProperty("client_id", issued);
				tokens.addProperty("subject", text(identity, "sub"));
				tokens.addProperty("email", text(identity, "email"));
				setExpiry(tokens);
				synchronized (this)
				{
					if (closed) { throw new IOException("Closed"); }
					persist(tokens);
					profile = tokens;
					generation++;
				}
				signingIn = false;
				done.accept(hasPermission(tokens) ? null : "Signed in, but ChatGPT plan access wasn't granted. Continue with ChatGPT again to authorize it.");
			}
			catch (Exception e) { if (!closed) { done.accept("ChatGPT sign-in didn't complete. It may have been declined, timed out, or be unavailable for this account. Try again."); } }
			finally
			{
				if (server != null) { server.stop(0); }
				callback = null;
				signingIn = false;
			}
		});
	}

	/** Called off the UI thread, serialized so rotating refresh tokens cannot race. */
	synchronized String accessToken() throws IOException
	{
		JsonObject p = profile;
		if (closed || p == null || !hasPermission(p) || text(p, "access_token").isEmpty())
		{
			throw new IOException("Continue with ChatGPT from the OSRS AI Buddy menu first.");
		}
		if (!p.has("expires_at") || p.get("expires_at").getAsLong() <= System.currentTimeMillis() / 1000 + 60)
		{
			if (text(p, "refresh_token").isEmpty()) { throw new IOException("Your ChatGPT session expired. Sign in again."); }
			JsonObject renewed = token(new FormBody.Builder().add("grant_type", "refresh_token")
				.add("client_id", text(p, "client_id")).add("refresh_token", text(p, "refresh_token"))
				.add("resource", RESOURCE).build());
			JsonObject merged = p.deepCopy();
			for (java.util.Map.Entry<String, JsonElement> entry : renewed.entrySet()) { merged.add(entry.getKey(), entry.getValue()); }
			setExpiry(merged);
			if (!hasPermission(merged)) { throw new IOException("ChatGPT plan permission is no longer enabled. Sign in again."); }
			persist(merged);
			profile = p = merged;
		}
		return text(p, "access_token");
	}

	void signOut(Consumer<String> done)
	{
		worker.execute(() -> {
			synchronized (this)
			{
				JsonObject p = profile;
				String warning = null;
				try
				{
					if (p != null && !text(p, "refresh_token").isEmpty())
					{
						JsonObject discovery = get(ISSUER + "/.well-known/openid-configuration");
						String endpoint = text(discovery, "revocation_endpoint");
						if (!endpoint.startsWith(ISSUER + "/")) { throw new IOException("Unexpected endpoint"); }
						try (Response r = http.newCall(new Request.Builder().url(endpoint).post(new FormBody.Builder()
							.add("token", text(p, "refresh_token")).add("token_type_hint", "refresh_token")
							.add("client_id", text(p, "client_id")).build()).build()).execute())
						{
							if (r.code() != 200) { throw new IOException("Revocation failed"); }
						}
					}
				}
				catch (Exception e) { warning = "Signed out locally. Remote revocation wasn't confirmed; disconnect OSRS AI Buddy in ChatGPT Settings."; }
				if (p != null)
				{
					JsonObject registration = new JsonObject();
					for (String key : new String[]{"client_id", "subject", "email"}) { registration.addProperty(key, text(p, key)); }
					profile = registration;
					try { persist(registration); }
					catch (IOException e) { warning = "Couldn't clear the saved session. Disconnect OSRS AI Buddy in ChatGPT Settings."; }
				}
				generation++;
				done.accept(warning);
			}
		});
	}

	synchronized void close()
	{
		closed = true;
		HttpServer server = callback;
		if (server != null) { server.stop(0); }
		worker.shutdownNow();
		profile = null;
		try { store.close(); } catch (IOException ignored) { }
	}

	private JsonObject token(FormBody form) throws IOException
	{
		try (Response r = http.newCall(new Request.Builder().url(ISSUER + "/api/accounts/oauth/token").post(form).build()).execute())
		{
			if (!r.isSuccessful() || r.body() == null) { throw new IOException("ChatGPT session could not be renewed. Sign in again."); }
			JsonObject value = ChatApi.fromJson(gson, r.body().string(), JsonObject.class);
			if (text(value, "access_token").isEmpty() || !"Bearer".equalsIgnoreCase(text(value, "token_type"))) { throw new IOException("Invalid token response"); }
			return value;
		}
	}

	private JsonObject get(String url) throws IOException
	{
		try (Response r = http.newCall(new Request.Builder().url(url).build()).execute())
		{
			if (!r.isSuccessful() || r.body() == null) { throw new IOException("OpenAI identity metadata unavailable"); }
			return ChatApi.fromJson(gson, r.body().string(), JsonObject.class);
		}
	}

	static String issuedClient(String pending, String returned) throws IOException
	{
		if (DYNAMIC.equals(pending))
		{
			if (returned == null || returned.isEmpty() || DYNAMIC.equals(returned)) { throw new IOException("Missing issued client ID"); }
			return returned;
		}
		if (returned != null && !pending.equals(returned)) { throw new IOException("Client registration mismatch"); }
		return pending;
	}

	static JsonObject verifyIdentity(String jwt, String client, String nonce, JsonObject jwks, Gson gson) throws Exception
	{
		String[] parts = jwt.split("\\.");
		if (parts.length != 3) { throw new IOException("Invalid identity token"); }
		JsonObject header = ChatApi.fromJson(gson, new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8), JsonObject.class);
		if (!"RS256".equals(text(header, "alg"))) { throw new IOException("Unexpected signing algorithm"); }
		boolean verified = false;
		for (JsonElement item : jwks.getAsJsonArray("keys"))
		{
			JsonObject key = item.getAsJsonObject();
			if (!text(header, "kid").equals(text(key, "kid")) || !"RSA".equals(text(key, "kty"))) { continue; }
			RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, Base64.getUrlDecoder().decode(text(key, "n"))),
				new BigInteger(1, Base64.getUrlDecoder().decode(text(key, "e"))));
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(spec));
			signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
			verified = signature.verify(Base64.getUrlDecoder().decode(parts[2]));
			break;
		}
		if (!verified) { throw new IOException("Identity signature verification failed"); }
		JsonObject claims = ChatApi.fromJson(gson, new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), JsonObject.class);
		boolean audience = false;
		if (claims.has("aud") && claims.get("aud").isJsonArray())
		{
			for (JsonElement a : claims.getAsJsonArray("aud")) { audience |= client.equals(a.getAsString()); }
		}
		else { audience = client.equals(text(claims, "aud")); }
		if (!ISSUER.equals(text(claims, "iss")) || !audience || !nonce.equals(text(claims, "nonce"))
			|| text(claims, "sub").isEmpty() || !claims.has("exp") || claims.get("exp").getAsLong() <= System.currentTimeMillis() / 1000
			|| (claims.has("azp") && !client.equals(text(claims, "azp")))) { throw new IOException("Identity claims verification failed"); }
		return claims;
	}

	static String text(JsonObject object, String key)
	{
		return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
	}

	static String challenge(String verifier) throws Exception
	{
		return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
	}

	private static String random()
	{
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private void setExpiry(JsonObject tokens)
	{
		long seconds = tokens.has("expires_in") ? tokens.get("expires_in").getAsLong() : 0;
		tokens.addProperty("expires_at", System.currentTimeMillis() / 1000 + seconds);
	}

	/** Only non-secret registration metadata reaches disk. Access, refresh and ID tokens stay in memory. */
	private synchronized void persist(JsonObject value) throws IOException
	{
		if (closed) { throw new IOException("Closed"); }
		JsonObject metadata = registrationOnly(value);
		metadata.addProperty("ext_agent_host_id", host);
		store.save(metadata);
	}

	static JsonObject registrationOnly(JsonObject value)
	{
		JsonObject metadata = new JsonObject();
		for (String field : new String[]{"ext_agent_host_id", "client_id", "subject", "email"})
		{
			String text = text(value, field);
			if (!text.isEmpty()) { metadata.addProperty(field, text); }
		}
		return metadata;
	}

	interface RegistrationStore
	{
		JsonObject open() throws Exception;
		void save(JsonObject metadata) throws IOException;
		void close() throws IOException;
	}
}

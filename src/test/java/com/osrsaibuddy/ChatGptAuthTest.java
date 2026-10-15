package com.osrsaibuddy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChatGptAuthTest
{
	private final Gson gson = new Gson();
	private static final class MemoryStore implements ChatGptAuth.RegistrationStore
	{
		JsonObject record = new JsonObject();
		public JsonObject open() { return record.deepCopy(); }
		public void save(JsonObject metadata) { record = metadata.deepCopy(); }
		public void close() { }
	}
	private KeyPair keys() throws Exception
	{
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		return generator.generateKeyPair();
	}
	private static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
	private static JsonObject jwks(KeyPair pair)
	{
		RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
		JsonObject key = new JsonObject();
		key.addProperty("kid", "test-key");
		key.addProperty("kty", "RSA");
		key.addProperty("n", b64(publicKey.getModulus().toByteArray()));
		key.addProperty("e", b64(publicKey.getPublicExponent().toByteArray()));
		JsonArray list = new JsonArray();
		list.add(key);
		JsonObject jwks = new JsonObject();
		jwks.add("keys", list);
		return jwks;
	}
	private JsonObject claims(String nonce)
	{
		JsonObject claims = new JsonObject();
		claims.addProperty("iss", ChatGptAuth.ISSUER);
		claims.addProperty("aud", "oaiapp_test");
		claims.addProperty("sub", "test-subject");
		claims.addProperty("email", "test@example.invalid");
		claims.addProperty("nonce", nonce);
		claims.addProperty("exp", System.currentTimeMillis() / 1000 + 600);
		return claims;
	}
	private String jwt(KeyPair pair, JsonObject claims) throws Exception
	{
		String unsigned = b64("{\"alg\":\"RS256\",\"kid\":\"test-key\"}".getBytes(StandardCharsets.UTF_8)) + "."
			+ b64(gson.toJson(claims).getBytes(StandardCharsets.UTF_8));
		Signature signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(pair.getPrivate());
		signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
		return unsigned + "." + b64(signer.sign());
	}
	@Test public void validatesSignatureIssuerAudienceExpiryAndNonce() throws Exception
	{
		KeyPair key = keys();
		assertEquals("test-subject", ChatGptAuth.verifyIdentity(jwt(key, claims("nonce")), "oaiapp_test", "nonce", jwks(key), gson).get("sub").getAsString());
		for (String field : new String[]{"iss", "aud", "nonce", "exp", "sub"})
		{
			JsonObject invalid = claims("nonce");
			if (field.equals("exp")) { invalid.addProperty(field, 1); }
			else { invalid.addProperty(field, field.equals("sub") ? "" : "wrong"); }
			try { ChatGptAuth.verifyIdentity(jwt(key, invalid), "oaiapp_test", "nonce", jwks(key), gson); fail("Accepted invalid " + field); }
			catch (IOException expected) { }
		}
		try { ChatGptAuth.verifyIdentity(jwt(keys(), claims("nonce")), "oaiapp_test", "nonce", jwks(key), gson); fail("Accepted wrong signing key"); }
		catch (IOException expected) { }
	}
	@Test public void validatesIssuedClientAndScopeIndependentlyOfIdentity() throws Exception
	{
		assertEquals("oaiapp_test", ChatGptAuth.issuedClient(ChatGptAuth.DYNAMIC, "oaiapp_test"));
		assertEquals("oaiapp_test", ChatGptAuth.issuedClient("oaiapp_test", null));
		for (String returned : new String[]{null, "", ChatGptAuth.DYNAMIC})
		{
			try { ChatGptAuth.issuedClient(ChatGptAuth.DYNAMIC, returned); fail("Accepted missing registration"); }
			catch (IOException expected) { }
		}
		try { ChatGptAuth.issuedClient("oaiapp_test", "different-client"); fail("Accepted replaced registration"); }
		catch (IOException expected) { }
		JsonObject tokens = new JsonObject();
		tokens.addProperty("scope", "openid profile email");
		assertFalse(ChatGptAuth.hasPermission(tokens));
		tokens.addProperty("scope", "openid " + ChatGptAuth.DIRECT);
		assertTrue(ChatGptAuth.hasPermission(tokens));
		assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
	}
	@Test public void browserCallbackPkceMemoryOnlyTokensRefreshAndSignOut() throws Exception
	{
		MemoryStore store = new MemoryStore();
		KeyPair pair = keys();
		AtomicReference<HttpUrl> authorize = new AtomicReference<>();
		AtomicReference<String> grant = new AtomicReference<>();
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
			Request request = chain.request();
			if (request.url().host().equals("127.0.0.1")) { return chain.proceed(request); }
			String path = request.url().encodedPath();
			String body;
			if (path.endsWith("jwks.json")) { body = gson.toJson(jwks(pair)); }
			else if (path.endsWith("openid-configuration")) { body = "{\"revocation_endpoint\":\"https://auth.openai.com/api/accounts/oauth/revoke\"}"; }
			else if (path.endsWith("/revoke")) { body = ""; }
			else if (path.endsWith("/token"))
			{
				okio.Buffer buffer = new okio.Buffer();
				request.body().writeTo(buffer);
				HttpUrl form = HttpUrl.get("http://127.0.0.1/?" + buffer.readUtf8());
				grant.set(form.queryParameter("grant_type"));
				assertEquals("oaiapp_test", form.queryParameter("client_id"));
				assertEquals(ChatGptAuth.RESOURCE, form.queryParameter("resource"));
				JsonObject tokens = new JsonObject();
				tokens.addProperty("access_token", "test-access");
				tokens.addProperty("refresh_token", "test-refresh");
				tokens.addProperty("token_type", "Bearer");
				tokens.addProperty("scope", "openid resource.invoke " + ChatGptAuth.DIRECT);
				tokens.addProperty("expires_in", "authorization_code".equals(grant.get()) ? 1 : 3600);
				if ("authorization_code".equals(grant.get()))
				{
					try
					{
						assertEquals(authorize.get().queryParameter("code_challenge"), ChatGptAuth.challenge(form.queryParameter("code_verifier")));
						assertEquals(authorize.get().queryParameter("redirect_uri"), form.queryParameter("redirect_uri"));
						tokens.addProperty("id_token", jwt(pair, claims(authorize.get().queryParameter("nonce"))));
					}
					catch (Exception e) { throw new IOException(e); }
				}
				body = gson.toJson(tokens);
			}
			else { throw new AssertionError("Unexpected network call: " + path); }
			return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
				.body(ResponseBody.create(MediaType.get("application/json"), body)).build();
		}).build();
		ChatGptAuth auth = new ChatGptAuth(http, gson, store);
		try
		{
			CompletableFuture<String> loaded = new CompletableFuture<>();
			auth.load(loaded::complete);
			assertNull(loaded.get(5, TimeUnit.SECONDS));
			CompletableFuture<String> done = new CompletableFuture<>();
			auth.signIn(url -> {
				HttpUrl a = HttpUrl.get(url);
				authorize.set(a);
				assertEquals(ChatGptAuth.DYNAMIC, a.queryParameter("client_id"));
				assertEquals("OSRS AI Buddy", a.queryParameter("agent_name_hint"));
				try
				{
					// A forged state cannot consume the pending authorization.
					HttpUrl.Builder callback = HttpUrl.get(a.queryParameter("redirect_uri")).newBuilder();
					try (Response invalid = http.newCall(new Request.Builder().url(callback.addQueryParameter("state", "forged").build()).build()).execute()) { assertEquals(400, invalid.code()); }
					HttpUrl valid = HttpUrl.get(a.queryParameter("redirect_uri")).newBuilder().addQueryParameter("state", a.queryParameter("state"))
						.addQueryParameter("code", "test-code").addQueryParameter("client_id", "oaiapp_test").build();
					try (Response accepted = http.newCall(new Request.Builder().url(valid).build()).execute()) { assertEquals(200, accepted.code()); }
				}
				catch (IOException e) { done.completeExceptionally(e); }
			}, done::complete);
			assertNull(done.get(10, TimeUnit.SECONDS));
			assertTrue(auth.connected());
			assertEquals("test@example.invalid", auth.account());
			assertEquals("test-access", auth.accessToken());
			assertEquals("refresh_token", grant.get());
			assertEquals("oaiapp_test", ChatGptAuth.text(store.record, "client_id"));
			assertFalse(store.record.has("access_token"));
			assertFalse(store.record.has("refresh_token"));
			assertFalse(store.record.has("id_token"));
			CompletableFuture<String> signedOut = new CompletableFuture<>();
			auth.signOut(signedOut::complete);
			assertNull(signedOut.get(5, TimeUnit.SECONDS));
			assertFalse(auth.connected());
			assertFalse(store.record.has("access_token"));
			assertFalse(store.record.has("refresh_token"));
			assertEquals("oaiapp_test", ChatGptAuth.text(store.record, "client_id"));
		}
		finally { auth.close(); }
	}
}



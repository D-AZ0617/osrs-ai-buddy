package com.osrsaibuddy;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.io.IOException;

/** Uses RuneLite's existing JNA runtime. Never uses machine-wide protection or a plaintext fallback. */
interface SessionProtection
{
    boolean available();
    byte[] protect(byte[] plaintext) throws IOException;
    byte[] unprotect(byte[] ciphertext) throws IOException;

    static SessionProtection windows()
    {
        return new SessionProtection()
        {
            public boolean available() { return Platform.isWindows(); }
            public byte[] protect(byte[] plaintext) throws IOException
            {
                if (!available()) { throw new IOException("Windows session protection unavailable"); }
                try { return Crypt32Util.cryptProtectData(plaintext, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN); }
                catch (RuntimeException | LinkageError e) { throw new IOException("Couldn't protect the ChatGPT session with Windows"); }
            }
            public byte[] unprotect(byte[] ciphertext) throws IOException
            {
                if (!available()) { throw new IOException("Windows session protection unavailable"); }
                try { return Crypt32Util.cryptUnprotectData(ciphertext, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN); }
                catch (RuntimeException | LinkageError e) { throw new IOException("Couldn't unlock the saved ChatGPT session"); }
            }
        };
    }
}

package com.distressedelk.lumi;

import android.content.SharedPreferences;

final class SecretStore {
    private static final String PREFIX = "secure_";
    private SecretStore() {}

    static void migratePrototypeSecrets(SharedPreferences prefs) {
        migrateOne(prefs, "openai_api_key");
        migrateOne(prefs, "opensource_api_key");
        migrateOne(prefs, "groq_api_key");
        migrateOne(prefs, "gemini_api_key");
        migrateOne(prefs, "openrouter_api_key");
        migrateOne(prefs, "cloudflare_api_key");
        migrateOne(prefs, "github_build_token");
        migrateLegacyProviderSecrets(prefs);
    }

    /**
     * Code251 compatibility sweep. Older Lumi experiments used a few different preference
     * labels for the same user-supplied credential. Recover them locally without ever logging
     * or exporting the secret, then remove the plaintext legacy entry.
     */
    private static void migrateLegacyProviderSecrets(SharedPreferences prefs) {
        String[] openAiLegacy = {"openai_key", "chatgpt_api_key", "openai_token"};
        for (String old : openAiLegacy) {
            String value = prefs.getString(old, "");
            if (value != null && !value.trim().isEmpty() && get(prefs, "openai_api_key").trim().isEmpty()) {
                put(prefs, "openai_api_key", value.trim());
                prefs.edit().remove(old).apply();
            }
        }
        String[] remoteLegacy = {"remote_ai_key", "ollama_api_key", "booster_api_key"};
        for (String old : remoteLegacy) {
            String value = prefs.getString(old, "");
            if (value != null && !value.trim().isEmpty() && get(prefs, "opensource_api_key").trim().isEmpty()) {
                put(prefs, "opensource_api_key", value.trim());
                prefs.edit().remove(old).apply();
            }
        }
        String[] urlLegacy = {"remote_ai_url", "booster_url", "ollama_url"};
        if (prefs.getString("opensource_url", "").trim().isEmpty()) {
            for (String old : urlLegacy) {
                String value = prefs.getString(old, "");
                if (value != null && !value.trim().isEmpty()) {
                    prefs.edit().putString("opensource_url", value.trim()).remove(old).apply();
                    break;
                }
            }
        }
    }

    private static void migrateOne(SharedPreferences prefs, String key) {
        String plain = prefs.getString(key, "");
        if (plain != null && !plain.trim().isEmpty()) {
            PrivateStore.write(prefs, PREFIX + key, plain.trim());
            prefs.edit().remove(key).apply();
        }
    }


    /** Code352 centralized credential firewall used before conversation, diagnostics, memory or model routing. */
    static boolean looksLikeCredential(String value) {
        if (value == null) return false;
        String v=value.trim();
        if (v.isEmpty()) return false;
        if (v.matches("(?i).*\\bBearer\\s+[A-Za-z0-9._~+\\-/=]{12,}.*")) return true;
        if (v.matches("(?i).*\\bsk-[A-Za-z0-9_-]{16,}\\b.*")) return true;
        if (v.matches("(?i).*\\bgsk_[A-Za-z0-9_-]{16,}\\b.*")) return true;
        if (v.matches(".*\\bAIza[0-9A-Za-z_-]{20,}\\b.*")) return true;
        if (v.matches("(?i).*\\b(?:github_pat_|ghp_|gho_|ghu_|ghs_|ghr_)[A-Za-z0-9_]{16,}\\b.*")) return true;
        if (v.matches("(?i).*(api[_ -]?key|access[_ -]?token|password|secret)\\s*[:=]\\s*[^\\s,;]{8,}.*")) return true;
        // A pasted provider credential is often a single high-entropy token with no prose.
        if (!v.contains(" ") && v.length()>=28 && v.length()<=512
                && v.matches("[A-Za-z0-9._~+\\-/=:_]+")
                && v.matches(".*[A-Za-z].*") && v.matches(".*[0-9].*")) return true;
        return false;
    }

    static String redact(String value) {
        if (value == null) return "";
        String x=value;
        x=x.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/=]{12,}","Bearer [REDACTED]");
        x=x.replaceAll("(?i)\\bsk-[A-Za-z0-9_-]{16,}\\b","[REDACTED_PROVIDER_KEY]");
        x=x.replaceAll("(?i)\\bgsk_[A-Za-z0-9_-]{16,}\\b","[REDACTED_GROQ_KEY]");
        x=x.replaceAll("\\bAIza[0-9A-Za-z_-]{20,}\\b","[REDACTED_GEMINI_KEY]");
        x=x.replaceAll("(?i)\\b(?:github_pat_|ghp_|gho_|ghu_|ghs_|ghr_)[A-Za-z0-9_]{16,}\\b","[REDACTED_GITHUB_TOKEN]");
        x=x.replaceAll("(?i)(api[_ -]?key|access[_ -]?token|token|password|secret)\\s*[:=]\\s*[^\\s,;]{8,}","$1=[REDACTED]");
        if (looksLikeCredential(x) && !x.contains(" ")) return "[REDACTED_CREDENTIAL]";
        return x;
    }

    static String providerHint(String value) {
        if(value==null) return "";
        String v=value.trim();
        if(v.startsWith("sk-or-")) return "openrouter";
        if(v.startsWith("gsk_")) return "groq";
        if(v.startsWith("AIza")) return "gemini";
        if(v.startsWith("sk-")) return "openai";
        return "";
    }

    static String get(SharedPreferences prefs, String key) { return PrivateStore.read(prefs, PREFIX + key); }
    static void put(SharedPreferences prefs, String key, String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) clear(prefs, key); else PrivateStore.write(prefs, PREFIX + key, v);
        prefs.edit().remove(key).apply();
    }
    static void clear(SharedPreferences prefs, String key) { prefs.edit().remove(PREFIX + key).remove(key).apply(); }
    static void remove(SharedPreferences prefs, String key) { clear(prefs, key); }
}

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { BUNDLED_MESSAGES, type LocaleCode, type TranslationMap } from "./messages";

export type { LocaleCode, TranslationMap };

interface I18nContextValue {
  locale: LocaleCode;
  setLocale: (next: LocaleCode) => void;
  toggleLocale: () => void;
  t: (key: string, fallback?: string) => string;
}

const I18nContext = createContext<I18nContextValue>({
  locale: "en",
  setLocale: () => undefined,
  toggleLocale: () => undefined,
  t: (key, fallback) => fallback ?? BUNDLED_MESSAGES.en[key] ?? key
});

function detectInitialLocale(): LocaleCode {
  try {
    const stored = globalThis.localStorage?.getItem("sqlflow.locale");
    if (stored === "cn" || stored === "en") {
      return stored;
    }
  } catch {
    // Ignore sandboxed storage access
  }
  return "en";
}

export const I18nProvider: React.FC<{ children: React.ReactNode; initialLocale?: LocaleCode }> = ({
  children,
  initialLocale
}) => {
  const [locale, setLocaleState] = useState<LocaleCode>(() => initialLocale ?? detectInitialLocale());
  const [remoteOverrides, setRemoteOverrides] = useState<Partial<Record<LocaleCode, TranslationMap>>>({});

  const setLocale = useCallback((next: LocaleCode) => {
    setLocaleState(next);
    try {
      globalThis.localStorage?.setItem("sqlflow.locale", next);
    } catch {
      // Ignore storage error
    }
  }, []);

  const toggleLocale = useCallback(() => {
    setLocale(locale === "en" ? "cn" : "en");
  }, [locale, setLocale]);

  useEffect(() => {
    let active = true;
    const loadRemoteJson = async () => {
      try {
        if (typeof globalThis.fetch !== "function") {
          return;
        }
        const res = await globalThis.fetch(`lang/${locale}.json`);
        if (res && res.ok) {
          const json = (await res.json()) as TranslationMap;
          if (active && json && typeof json === "object") {
            setRemoteOverrides((prev) => ({
              ...prev,
              [locale]: { ...BUNDLED_MESSAGES[locale], ...json }
            }));
          }
        }
      } catch {
        // Use bundled fallback dictionary seamlessly in offline/test environments
      }
    };
    void loadRemoteJson();
    return () => {
      active = false;
    };
  }, [locale]);

  const dictionary = useMemo<TranslationMap>(
    () => remoteOverrides[locale] ?? BUNDLED_MESSAGES[locale],
    [locale, remoteOverrides]
  );

  const t = useCallback(
    (key: string, fallback?: string): string => {
      return dictionary[key] ?? BUNDLED_MESSAGES.en[key] ?? fallback ?? key;
    },
    [dictionary]
  );

  const value = useMemo<I18nContextValue>(
    () => ({
      locale,
      setLocale,
      toggleLocale,
      t
    }),
    [locale, setLocale, toggleLocale, t]
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
};

export function useI18n(): I18nContextValue {
  return useContext(I18nContext);
}

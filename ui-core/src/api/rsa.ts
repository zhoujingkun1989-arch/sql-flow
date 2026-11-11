/**
 * Clean-Room 1024-Bit RSA `JSEncrypt` Helper (`PKCS#1 v1.5` Base64).
 *
 * STRICT CLEAN-ROOM & WIRE INVARIANT #7 (`00 §1.2`, `00 §3.3 #7`, `04 §12.1 #4`):
 * 1. Contains ZERO shipped Gudu cryptographic keys — uses a freshly generated clean-room
 *    1024-bit RSA SubjectPublicKeyInfo key by default (overridable at runtime via `setDefaultRsaPublicKey`
 *    or per-client options).
 * 2. Applied STRICTLY AND EXCLUSIVELY to `DataSource.password` on:
 *    - `POST /database/testConnection`
 *    - `POST /sqlflow/job/submitUserJob`
 *    - `POST /sqlflow/job/submitPersistJob`
 *    User authentication/admin endpoints (`/user/register`, `/user/modifyPassword`, `/user/addUser`,
 *    `/user/resetPassword`) MUST transmit plaintext passwords without client-side RSA encryption.
 */

import JSEncrypt from "jsencrypt";
import type { DataSource } from "../types/api.js";

/**
 * Clean-room 1024-bit RSA SubjectPublicKeyInfo (`spki` Base64 DER) default public key.
 * Generated specifically for this clean-room reconstruction.
 */
export const CLEAN_ROOM_DEFAULT_RSA_1024_PUBLIC_KEY =
  "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCyzJA0XpDK9QRbM1s+VhqJfkF27ROVatJMHoukeypNnXstjNKIsSlaM/PaZ47sYVzfasXJ/3/b5hiefasI6emFmWUt/H2RD1t9mIZTH7cLw9ZD0cbukN9tNCZrLkEb0gC4rm4pz0L7pW5LZ1RUl5TWsSKmzc2s0RmVMZi3imaX9QIDAQAB";

export const RSA_ENCRYPTED_ENDPOINTS = [
  "/database/testConnection",
  "/sqlflow/job/submitUserJob",
  "/sqlflow/job/submitPersistJob"
] as const;

export const PLAINTEXT_PASSWORD_ENDPOINTS = [
  "/user/register",
  "/user/modifyPassword",
  "/user/addUser",
  "/user/resetPassword"
] as const;

let activeDefaultPublicKey: string = CLEAN_ROOM_DEFAULT_RSA_1024_PUBLIC_KEY;

export function getDefaultRsaPublicKey(): string {
  return activeDefaultPublicKey;
}

export function setDefaultRsaPublicKey(publicKey: string): void {
  const trimmed = publicKey.trim();
  if (!trimmed) {
    throw new Error("RSA public key must not be empty");
  }
  activeDefaultPublicKey = trimmed;
}

export function resetDefaultRsaPublicKey(): void {
  activeDefaultPublicKey = CLEAN_ROOM_DEFAULT_RSA_1024_PUBLIC_KEY;
}

function ensureNavigatorShim(): void {
  const g = globalThis as Record<string, unknown>;
  if (typeof g.navigator === "undefined") {
    g.navigator = { appName: "Netscape", userAgent: "Mozilla/5.0" };
  }
  if (typeof g.window === "undefined") {
    g.window = globalThis;
  }
}

/**
 * Encrypts a database connection `password` using `JSEncrypt` (`PKCS#1 v1.5`, Base64 output).
 * Empty or undefined passwords return `""` untouched.
 */
export function encryptRsaPassword(
  plainPassword?: string | null,
  publicKeyOverride?: string
): string {
  if (plainPassword === undefined || plainPassword === null || plainPassword === "") {
    return "";
  }
  ensureNavigatorShim();
  const keyToUse = (publicKeyOverride ?? activeDefaultPublicKey).trim();
  const crypt = new JSEncrypt();
  crypt.setPublicKey(keyToUse);
  const encrypted = crypt.encrypt(plainPassword);
  if (!encrypted || typeof encrypted !== "string") {
    throw new Error("JSEncrypt failed to encrypt DataSource password with configured RSA public key");
  }
  return encrypted;
}

/**
 * Clones a `DataSource` (or partial connection object), RSA-encrypting `password` if non-empty.
 */
export function encryptDataSourcePassword<T extends Partial<DataSource>>(
  dataSource: T,
  publicKeyOverride?: string
): T {
  if (!dataSource.password) {
    return { ...dataSource };
  }
  return {
    ...dataSource,
    password: encryptRsaPassword(dataSource.password, publicKeyOverride)
  };
}

# Legacy signing flow

The `vectras.jks` signing identity belongs to the legacy Vectras/Termux:X11 loader. It is **not** required for the new `com.virtualpcvm` backend or ordinary debug builds.

## Normal builds

Do not provide `legacySigningEnabled`. The Android Gradle Plugin's standard debug signing configuration is used for `debug`, and the legacy `shell-loader` variants are disabled.

CI must not generate or commit `vectras.jks`.

## Explicit legacy build

The legacy flow is enabled only with:

```text
-PlegacySigningEnabled=true
```

The keystore must be supplied from a secure location. The default path is `../vectras.jks` for local compatibility, but CI should provide an explicit secure file path with `legacyStoreFile`.

Credentials can be supplied as Gradle properties or environment-backed Gradle project properties:

- `legacyStoreFile`
- `legacyStorePassword` / `LEGACY_SIGNING_STORE_PASSWORD`
- `legacyKeyAlias` / `LEGACY_SIGNING_KEY_ALIAS`
- `legacyKeyPassword` / `LEGACY_SIGNING_KEY_PASSWORD`

For CI, prefer protected GitHub environment/secret material and a secure temporary keystore file. Never commit the private keystore or its passwords.

When enabled, the legacy `shell-loader` uses the certificate from that same configured signing identity to generate its `SIGNATURE` constant. This keeps the legacy signature check tied to the actual selected key rather than to a generated placeholder.

# Local Keycloak

This directory is for local development only. The Compose service imports
`hansul-realm.json` into an ephemeral Keycloak database on container creation.

## Start

```powershell
docker compose up -d postgres keycloak
```

If local port 5432 is occupied:

```powershell
$env:POSTGRES_PORT = "55432"
docker compose up -d postgres keycloak
$env:DATABASE_URL = "jdbc:postgresql://localhost:55432/hansul"
```

The default OAuth settings are:

- issuer: `http://10.0.2.2:8180/realms/hansul`
- Android client: `hansul-android` (public client, authorization code + PKCE S256)
- Android redirect URI: `com.hansul.app:/oauth2redirect`
- API audience: `hansul-api`
- server JWKS URL: `http://localhost:8180/realms/hansul/protocol/openid-connect/certs`

`10.0.2.2` is the Android emulator address for the host machine. The resource
server validates that canonical issuer but fetches signing keys through
`localhost`. Override `OIDC_ISSUER_URI`, `OIDC_JWK_SET_URI`, and
`KEYCLOAK_ISSUER_BASE_URL` together for a different environment.

For automated local smoke tests only, the realm contains client
`hansul-smoke` and user `dev-user` / `dev-password`. The Keycloak administrator
is `admin` / `admin`. Do not provision those credentials or enable the password
grant in a deployed environment; use HTTPS and environment-specific secrets.

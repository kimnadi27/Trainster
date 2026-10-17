import base64
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import secrets


PASSWORD_ITERATIONS = 600_000
# Used for constant-cost verification when a login does not exist.
DUMMY_PASSWORD_HASH = "pbkdf2_sha256$600000$AAAAAAAAAAAAAAAAAAAAAA==$pP7jj7dszaZE8fQvTlddOOg2nNZ8zhl4IV0TcjJdSRk="


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, PASSWORD_ITERATIONS)
    return "$".join(("pbkdf2_sha256", str(PASSWORD_ITERATIONS),
                     base64.b64encode(salt).decode(), base64.b64encode(digest).decode()))


def verify_password(password: str, encoded: str) -> bool:
    try:
        algorithm, iterations, salt, expected = encoded.split("$")
        if algorithm != "pbkdf2_sha256":
            return False
        actual = hashlib.pbkdf2_hmac("sha256", password.encode(), base64.b64decode(salt), int(iterations))
        return hmac.compare_digest(actual, base64.b64decode(expected))
    except (ValueError, TypeError):
        return False


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def create_session(connection, user_id: str, days: int) -> str:
    token = secrets.token_urlsafe(32)
    now = utcnow()
    connection.execute("DELETE FROM sessions WHERE expires_at <= ?", (now.isoformat(),))
    connection.execute("INSERT INTO sessions (token_hash,user_id,expires_at) VALUES (?,?,?)",
                       (token_hash(token), user_id, (now + timedelta(days=days)).isoformat()))
    return token

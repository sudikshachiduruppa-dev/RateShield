import { useState } from "react";
import "./App.css";

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "http://localhost:8080").replace(/\/+$/, "");

// ---------------------------------------------------------------------------
// Dashboard — shown after successful login
// ---------------------------------------------------------------------------
function Dashboard({ username, token, onLogout }) {
  const [probeLoading, setProbeLoading] = useState(false);
  const [probeResult, setProbeResult] = useState(null); // { status, ok, data, limit, remaining }

  const probe = async () => {
    setProbeLoading(true);
    setProbeResult(null);

    try {
      const response = await fetch(`${API_BASE_URL}/api/ratelimit/test`, {
        method: "GET",
        headers: {
          Authorization: `Bearer ${token}`,
        },
      });

      const limit     = response.headers.get("X-RateLimit-Limit");
      const remaining = response.headers.get("X-RateLimit-Remaining");
      const retryAfter = response.headers.get("Retry-After");

      // Safe JSON parse — 401/429 may have a body or may not
      let data = null;
      const contentType = response.headers.get("Content-Type") || "";
      if (contentType.includes("application/json")) {
        try {
          data = await response.json();
        } catch {
          data = null;
        }
      }

      setProbeResult({
        status: response.status,
        ok: response.ok,
        data,
        limit,
        remaining,
        retryAfter,
      });
    } catch (err) {
      setProbeResult({ networkError: err.message });
    } finally {
      setProbeLoading(false);
    }
  };

  const statusClass =
    probeResult == null
      ? ""
      : probeResult.networkError
        ? "error"
        : probeResult.ok
          ? "success"
          : probeResult.status === 429
            ? "warn"
            : "error";

  return (
    <main className="app-shell">

      {/* Top bar */}
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">RS</span>
          <div>
            <div className="brand-name">RateShield</div>
            <div className="brand-subtitle">REQUEST CONTROL SYSTEM</div>
          </div>
        </div>

        <div className="topbar-right">
          <div className="system-status">
            <span className="status-dot"></span>
            SYSTEM ONLINE
          </div>
          <button id="logout-button" className="logout-button" onClick={onLogout}>
            SIGN OUT →
          </button>
        </div>
      </header>


      {/* Dashboard grid */}
      <section className="dash-grid">

        {/* Identity panel */}
        <aside className="dash-identity">

          <div className="eyebrow">
            <span>02</span>
            SESSION
          </div>

          <h1 className="dash-heading">
            Welcome,<br />
            <span className="dash-username">{username}</span>
          </h1>

          <div className="system-specs">
            <div className="spec-row">
              <span>USER</span>
              <strong>{username}</strong>
            </div>
            <div className="spec-row">
              <span>AUTH</span>
              <strong>JWT / BEARER</strong>
            </div>
            <div className="spec-row">
              <span>SCOPE</span>
              <strong>RATE-LIMITED</strong>
            </div>
            <div className="spec-row">
              <span>ENDPOINT</span>
              <strong>/api/ratelimit/test</strong>
            </div>
          </div>

          <div className="service-list" style={{ marginTop: 30 }}>
            <div className="service-item">
              <span className="service-indicator"></span>
              AUTHENTICATED
            </div>
            <div className="service-item">
              <span className="service-indicator"></span>
              RATE LIMITING ACTIVE
            </div>
          </div>

        </aside>


        {/* Probe panel */}
        <section className="dash-probe">

          <div className="panel-header">
            <div>
              <span className="panel-index">PROBE / 002</span>
              <h2>Rate limit tester</h2>
            </div>
            <div className="panel-state">LIVE</div>
          </div>

          <p className="probe-description">
            Fire a request to the protected endpoint. Each hit consumes one
            token from your personal bucket. After the bucket is exhausted,
            requests return HTTP 429 until tokens refill.
          </p>

          <button
            id="probe-button"
            className="submit-button"
            onClick={probe}
            disabled={probeLoading}
          >
            <span>{probeLoading ? "PROBING..." : "SEND PROBE REQUEST"}</span>
            <span className="button-arrow">→</span>
          </button>


          {/* Result card */}
          {probeResult && (
            <div className={`probe-result ${statusClass}`}>

              {probeResult.networkError ? (
                <>
                  <span className="response-label">NETWORK ERROR</span>
                  <span className="probe-status-code">—</span>
                  <p className="probe-error-msg">{probeResult.networkError}</p>
                </>
              ) : (
                <>
                  <div className="probe-status-row">
                    <span className="response-label">HTTP STATUS</span>
                    <span className="probe-status-code">{probeResult.status}</span>
                  </div>

                  {/* Rate-limit headers */}
                  <div className="probe-headers">
                    <div className="probe-header-row">
                      <span className="probe-header-name">X-RateLimit-Limit</span>
                      <span className="probe-header-value">
                        {probeResult.limit ?? "—"}
                      </span>
                    </div>
                    <div className="probe-header-row">
                      <span className="probe-header-name">X-RateLimit-Remaining</span>
                      <span className="probe-header-value">
                        {probeResult.remaining ?? "—"}
                      </span>
                    </div>
                    {probeResult.retryAfter && (
                      <div className="probe-header-row">
                        <span className="probe-header-name">Retry-After</span>
                        <span className="probe-header-value">
                          {probeResult.retryAfter}s
                        </span>
                      </div>
                    )}
                  </div>

                  {/* Body */}
                  {probeResult.data && (
                    <div className="probe-body">
                      <span className="response-label">RESPONSE BODY</span>
                      <pre className="probe-json">
                        {JSON.stringify(probeResult.data, null, 2)}
                      </pre>
                    </div>
                  )}

                  {!probeResult.ok && (
                    <p className="probe-error-msg">
                      {probeResult.status === 401
                        ? "Session expired or token rejected (HTTP 401). Please sign out and log in again."
                        : probeResult.status === 429
                          ? "Rate limit exceeded (HTTP 429). Wait for tokens to refill."
                          : `Request failed with status ${probeResult.status}.`}
                    </p>
                  )}
                </>
              )}

            </div>
          )}

        </section>

      </section>


      {/* Footer */}
      <footer className="footer">
        <span>RATESHIELD / DASHBOARD</span>
        <span>{username.toUpperCase()}</span>
        <span>API : 8080</span>
        <span>UI : 5173</span>
      </footer>

    </main>
  );
}


// ---------------------------------------------------------------------------
// Auth form — shown when not authenticated
// ---------------------------------------------------------------------------
function App() {
  // authState: null = unauthenticated; { username, token } = authenticated
  const [authState, setAuthState] = useState(() => {
    const token    = localStorage.getItem("rateshield_token");
    const username = localStorage.getItem("rateshield_username");
    return token && username ? { token, username } : null;
  });

  const [isLogin, setIsLogin] = useState(true);

  const [formData, setFormData] = useState({
    username: "",
    password: "",
  });

  const [message, setMessage]       = useState("");
  const [messageType, setMessageType] = useState("");
  const [loading, setLoading]       = useState(false);

  const handleChange = (event) => {
    const { name, value } = event.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
    setMessage("");
  };

  const switchMode = (loginMode) => {
    setIsLogin(loginMode);
    setMessage("");
    setMessageType("");
    setFormData({ username: "", password: "" });
  };

  const handleLogout = () => {
    localStorage.removeItem("rateshield_token");
    localStorage.removeItem("rateshield_username");
    setAuthState(null);
    setFormData({ username: "", password: "" });
    setMessage("");
    setMessageType("");
    setIsLogin(true);
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    setLoading(true);
    setMessage("");
    setMessageType("");

    const endpoint = isLogin
      ? `${API_BASE_URL}/api/auth/login`
      : `${API_BASE_URL}/api/auth/signup`;

    try {
      const response = await fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(formData),
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.message || "Authentication failed");
      }

      // Store credentials
      if (data.token) {
        localStorage.setItem("rateshield_token", data.token);
      }
      if (data.username) {
        localStorage.setItem("rateshield_username", data.username);
      }

      if (isLogin) {
        // Navigate to dashboard
        setAuthState({ token: data.token, username: data.username });
      } else {
        // Signup success: stay on form, let user log in
        setMessage(data.message || "Account created. You can now log in.");
        setMessageType("success");
        setFormData({ username: "", password: "" });
        setIsLogin(true);
      }
    } catch (error) {
      setMessage(error.message);
      setMessageType("error");
    } finally {
      setLoading(false);
    }
  };

  // ------- Render dashboard if authenticated -------
  if (authState) {
    return (
      <Dashboard
        username={authState.username}
        token={authState.token}
        onLogout={handleLogout}
      />
    );
  }

  // ------- Render auth form -------
  return (
    <main className="app-shell">

      {/* Top system bar */}
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">RS</span>
          <div>
            <div className="brand-name">RateShield</div>
            <div className="brand-subtitle">REQUEST CONTROL SYSTEM</div>
          </div>
        </div>

        <div className="system-status">
          <span className="status-dot"></span>
          SYSTEM ONLINE
        </div>
      </header>


      {/* Main content */}
      <section className="main-grid">

        {/* Left information panel */}
        <section className="intro-panel">

          <div className="eyebrow">
            <span>01</span>
            ACCESS CONTROL
          </div>

          <h1>
            Protect every
            <br />
            request.
          </h1>

          <p className="intro-text">
            RateShield is an in-memory token bucket
            rate limiting system designed to control
            request traffic before it reaches your
            application.
          </p>

          <div className="system-specs">
            <div className="spec-row">
              <span>ALGORITHM</span>
              <strong>TOKEN BUCKET</strong>
            </div>
            <div className="spec-row">
              <span>STATE</span>
              <strong>IN-MEMORY</strong>
            </div>
            <div className="spec-row">
              <span>GATE</span>
              <strong>SERVLET FILTER</strong>
            </div>
            <div className="spec-row">
              <span>DATABASE</span>
              <strong>MYSQL</strong>
            </div>
          </div>

          <div className="service-list">
            <div className="service-item">
              <span className="service-indicator"></span>
              AUTHENTICATION
            </div>
            <div className="service-item">
              <span className="service-indicator"></span>
              RATE LIMITING
            </div>
            <div className="service-item">
              <span className="service-indicator"></span>
              OBSERVABILITY
            </div>
          </div>

        </section>


        {/* Authentication panel */}
        <section className="auth-panel">

          <div className="panel-header">
            <div>
              <span className="panel-index">AUTH / 001</span>
              <h2>{isLogin ? "Sign in" : "Create account"}</h2>
            </div>
            <div className="panel-state">
              {isLogin ? "LOGIN" : "REGISTER"}
            </div>
          </div>


          {/* Login / Signup switch */}
          <div className="mode-switch">
            <button
              className={isLogin ? "mode active" : "mode"}
              onClick={() => switchMode(true)}
              type="button"
            >
              LOGIN
            </button>
            <button
              className={!isLogin ? "mode active" : "mode"}
              onClick={() => switchMode(false)}
              type="button"
            >
              SIGN UP
            </button>
          </div>


          <form onSubmit={handleSubmit}>

            <div className="field">
              <label htmlFor="username">
                <span>01</span>
                USERNAME
              </label>
              <input
                id="username"
                name="username"
                type="text"
                placeholder="enter identifier"
                value={formData.username}
                onChange={handleChange}
                required
              />
            </div>

            <div className="field">
              <label htmlFor="password">
                <span>02</span>
                PASSWORD
              </label>
              <input
                id="password"
                name="password"
                type="password"
                placeholder="enter credential"
                value={formData.password}
                onChange={handleChange}
                required
              />
            </div>

            <button
              id="auth-submit"
              className="submit-button"
              type="submit"
              disabled={loading}
            >
              <span>
                {loading
                  ? "PROCESSING..."
                  : isLogin
                    ? "AUTHENTICATE"
                    : "CREATE ACCOUNT"}
              </span>
              <span className="button-arrow">→</span>
            </button>

          </form>


          {message && (
            <div className={`response-message ${messageType}`}>
              <span className="response-label">RESPONSE</span>
              <span>{message}</span>
            </div>
          )}


          <div className="auth-footer">
            <span>
              {isLogin ? "NO ACCOUNT?" : "ALREADY REGISTERED?"}
            </span>
            <button type="button" onClick={() => switchMode(!isLogin)}>
              {isLogin ? "CREATE ONE" : "SIGN IN"}
            </button>
          </div>

        </section>

      </section>


      {/* Bottom technical footer */}
      <footer className="footer">
        <span>RATESHIELD / AUTHENTICATION</span>
        <span>LOCAL INSTANCE</span>
        <span>API : 8080</span>
        <span>UI : 5173</span>
      </footer>

    </main>
  );
}

export default App;
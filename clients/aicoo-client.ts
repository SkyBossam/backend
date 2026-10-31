/** Browser API adapter. Keep tokens in memory; never put refresh tokens in URLs or localStorage. */
export type UUID = string;
export type Severity = 'immediate' | 'caution' | 'stable' | 'unknown';
export type EventStatus = 'new' | 'acknowledged' | 'checking' | 'resolved' | 'unverifiable';
export type Capability = 'child:read' | 'child:manage' | 'routine:read' | 'routine:write' | 'cry:read' | 'cry:feedback' | 'monitor:live' | 'detection:read' | 'detection:ack' | 'detection:resolve' | 'notification:receive';
export interface Session { accessToken: string; refreshToken: string; expiresIn: number; userId: UUID; sessionId: UUID; username: string; email: string | null; emailVerified: boolean; workspaceId?: UUID; membershipId?: UUID; }
export interface Child { id: UUID; displayName: string; birthDate: string; workspaceChildId: UUID; capabilities: Capability[] }
export interface Detection { id: UUID; workspaceId: UUID; childId: UUID; type: string; severity: Severity; status: EventStatus; summary: string; startedAt: string; endedAt: string | null; acknowledgedAt?: string | null }
export interface Page<T> { items: T[]; nextCursor: string | null }
export interface Routine { id: UUID; childId: UUID; type: 'sleep' | 'wake' | 'play' | 'soothe' | 'custom'; title: string; scheduledAt: string | null; endedAt: string | null; status: 'todo' | 'in_progress' | 'done'; completedAt: string | null }
export interface Monitoring { childId: UUID; streamStatus: 'live' | 'offline' | 'unverifiable'; streamUrl: null; safety: Record<'posture' | 'face' | 'edge' | 'objects', Severity>; sensors: { type: string; connected: boolean; value: number | null; unit: string; observedAt: string | null }[] }
export interface Recommendation { eventId: UUID; status: 'pending' | 'ready' | 'failed' | 'insufficient_data'; causes: { id: UUID; causeKey: string; label: string; rank: number; reason: string }[]; actions: { id: UUID; actionKey: string; title: string; explanation: string; instructions: string[] }[]; fallbackGuide: string[]; notice: string }
export interface RealtimeMessage { messageId: UUID; sequence: number; type: string; workspaceId: UUID; childId: UUID; eventId: UUID; severity: Severity; occurredAt: string }
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public requestId?: string) { super(message); }
}

export class AicooClient {
  private session: Session | null = null;
  private refreshing: Promise<Session> | null = null;
  workspaceId: UUID | null = null;
  constructor(readonly baseUrl: string) {}
  setSession(session: Session) { this.session = session; if (session.workspaceId) this.workspaceId = session.workspaceId; }
  clearSession() { this.session = null; this.workspaceId = null; }
  private headers(body?: unknown, key?: string) {
    const headers = new Headers();
    if (body !== undefined) headers.set('Content-Type', 'application/json');
    if (this.session) headers.set('Authorization', `Bearer ${this.session.accessToken}`);
    if (this.workspaceId) headers.set('X-Workspace-Id', this.workspaceId);
    if (key) headers.set('Idempotency-Key', key);
    return headers;
  }
  async request<T>(method: string, path: string, body?: unknown, key?: string, retry = true): Promise<T> {
    const headers = this.headers(body, key);
    const requestToken = this.session?.accessToken;
    if (path.startsWith('/auth/')) headers.delete('Authorization');
    // Logout is the only authenticated auth endpoint.
    if (path === '/auth/logout' && this.session) headers.set('Authorization', `Bearer ${this.session.accessToken}`);
    const response = await fetch(`${this.baseUrl.replace(/\/$/, '')}/api/v1${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store', credentials: 'omit' });
    if (response.status === 401 && retry && this.session && !path.startsWith('/auth/')) {
      if (this.session.accessToken === requestToken) await this.refresh();
      return this.request<T>(method, path, body, key, false);
    }
    const data = await response.json();
    if (!response.ok) throw new ApiError(response.status, data.error?.code ?? 'API_ERROR', data.error?.message ?? '요청에 실패했어요.', data.error?.requestId);
    return data as T;
  }
  async signup(username: string, password: string) { const session = await this.request<Session>('POST', '/auth/signup', { username, password }); this.setSession(session); return session; }
  async login(username: string, password: string) { const session = await this.request<Session>('POST', '/auth/login', { username, password }); this.setSession(session); this.workspaceId = null; return session; }
  private refresh() {
    if (this.refreshing) return this.refreshing;
    if (!this.session) return Promise.reject(new ApiError(401, 'LOGIN_REQUIRED', '로그인이 필요해요.'));
    this.refreshing = this.request<Session>('POST', '/auth/refresh', { refreshToken: this.session.refreshToken }, undefined, false)
      .then(session => { this.setSession(session); return session; })
      .catch(error => { this.clearSession(); throw error; })
      .finally(() => { this.refreshing = null; });
    return this.refreshing;
  }
  async logout() { try { await this.request('POST', '/auth/logout', {}); } finally { this.clearSession(); } }
  verifyEmail(token: string) { return this.request('POST', '/auth/verify-email', { token }); }
  workspaces() { return this.request<{ items: { id: UUID; type: 'PERSONAL' | 'INSTITUTION'; name: string; membershipId: UUID; role: string }[] }>('GET', '/me/workspaces'); }
  children() { return this.request<{ items: Child[] }>('GET', '/children'); }
  child(id: UUID) { return this.request<Child>('GET', `/children/${encodeURIComponent(id)}`); }
  createChild(body: { displayName: string; birthDate: string }, key = crypto.randomUUID()) { return this.request<Child>('POST', '/children', body, key); }
  dashboard(childId?: UUID) { return this.request<{ items: (Child & { severity: Severity; headline: string; unacknowledgedCount: number | null; priorityEvent: Detection | null })[] }>('GET', `/dashboard${childId ? `?childId=${encodeURIComponent(childId)}` : ''}`); }
  events(child: UUID, type?: string, cursor?: string) { const query = new URLSearchParams(); if (type) query.set('type', type); if (cursor) query.set('cursor', cursor); return this.request<Page<Detection>>('GET', `/children/${encodeURIComponent(child)}/events?${query}`); }
  event(id: UUID) { return this.request<Detection>('GET', `/events/${encodeURIComponent(id)}`); }
  recommendations(event: UUID) { return this.request<Recommendation>('GET', `/events/${encodeURIComponent(event)}/recommendations`); }
  feedback(event: UUID, body: { attempts: { actionId: UUID; state: 'attempted' | 'not_attempted'; note?: string }[]; response: 'stopped' | 'reduced' | 'continues' | 'unknown'; note?: string }, key: string) { return this.request('POST', `/events/${encodeURIComponent(event)}/feedback`, body, key); }
  acknowledge(event: UUID) { return this.request('POST', `/events/${encodeURIComponent(event)}/acknowledgements`, {}); }
  resolve(event: UUID, body: { result: 'checked_safe' | 'intervention_done' | 'false_alarm' | 'needs_help'; note?: string }, key: string) { return this.request('POST', `/events/${encodeURIComponent(event)}/resolution`, body, key); }
  routines(child: UUID, date?: string) { return this.request<{ items: Routine[] }>('GET', `/children/${encodeURIComponent(child)}/routines${date ? `?date=${encodeURIComponent(date)}` : ''}`); }
  updateRoutine(child: UUID, routine: UUID, body: Partial<Pick<Routine, 'title' | 'status' | 'scheduledAt' | 'endedAt'>>) { return this.request<Routine>('PATCH', `/children/${encodeURIComponent(child)}/routines/${encodeURIComponent(routine)}`, body); }
  monitoring(child: UUID) { return this.request<Monitoring>('GET', `/children/${encodeURIComponent(child)}/monitoring`); }
  streamToken(child: UUID) { return this.request<{ token: string; gatewayUrl: string; expiresAt: string; reauthorizeAfterSeconds: number }>('POST', `/children/${encodeURIComponent(child)}/stream-token`, {}); }

  /** One SSE connection. On completion reconnect with last sequence; refresh REST data on reconnect. */
  async realtime(onMessage: (message: RealtimeMessage) => void, signal: AbortSignal, after = 0): Promise<number> {
    const headers = this.headers(); headers.set('Accept', 'text/event-stream'); if (after) headers.set('Last-Event-ID', String(after));
    const response = await fetch(`${this.baseUrl.replace(/\/$/, '')}/api/v1/realtime`, { headers, signal, cache: 'no-store', credentials: 'omit' });
    if (!response.ok) { const data = await response.json(); throw new ApiError(response.status, data.error?.code, data.error?.message, data.error?.requestId); }
    if (!response.body) throw new Error('실시간 스트림이 없어요.');
    const reader = response.body.getReader(), decoder = new TextDecoder(); let buffer = '', sequence = after;
    try {
      while (!signal.aborted) {
        const { value, done } = await reader.read(); if (done) break;
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
        let boundary: number;
        while ((boundary = buffer.indexOf('\n\n')) !== -1) {
          const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
          const data = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
          if (data) { const message = JSON.parse(data) as RealtimeMessage; if (message.sequence > sequence) { onMessage(message); sequence = message.sequence; } }
        }
        if (buffer.length > 65536) throw new Error('실시간 메시지가 너무 커요.');
      }
    } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
    return sequence;
  }
}

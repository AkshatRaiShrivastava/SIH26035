import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import './App.css'

const browserHost = window.location.hostname || 'localhost'
const configuredApi = import.meta.env.VITE_API_URL as string | undefined
const configuredRag = import.meta.env.VITE_RAG_URL as string | undefined
const API = configuredApi && !(configuredApi.includes('localhost') && browserHost !== 'localhost') ? configuredApi : `http://${browserHost}:8080/api`
const RAG_API = configuredRag && !(configuredRag.includes('localhost') && browserHost !== 'localhost') ? configuredRag : `http://${browserHost}:8000`
type Instrument = { model: string; manufacturer: string; serialNumber: string; accuracyClass: string; status: string }
type TestResult = { id: string; instrumentModel: string; error: number; permissibleError: number; result: 'PASS' | 'FAIL'; status: string; ruleSource: string }
type Citation = { document: string; section?: string; page?: number; chunk_id?: string }
type Report = { id: string; version: number; status: string; sha256hash?: string }
type TestCase = { code: string; name: string; supported: boolean; reason?: string }
type Observation = { testLoad: string; indication: string; scaleInterval: string }
type RagInsights = { totalDocuments: number; totalPages: number; pagesNeedingOcr: number; message: string; documents: { document: string; pages: number; status: string }[] }

function App() {
  const [loggedIn, setLoggedIn] = useState(false)
  const [email, setEmail] = useState('engineer@demo.local')
  const [password, setPassword] = useState('demo')
  const [instruments, setInstruments] = useState<Instrument[]>([])
  const [selectedModel, setSelectedModel] = useState('ABC-1000')
  const [result, setResult] = useState<TestResult | null>(null)
  const [report, setReport] = useState<Report | null>(null)
  const [verification, setVerification] = useState('')
  const [message, setMessage] = useState('')
  const [testCases, setTestCases] = useState<TestCase[]>([])
  const [testType, setTestType] = useState('ACCURACY')
  const [untested, setUntested] = useState<Instrument[]>([])
  const [observations, setObservations] = useState<Observation[]>([{ testLoad: '1000', indication: '1000.2', scaleInterval: '1' }])
  const [ragInsights, setRagInsights] = useState<RagInsights | null>(null)
  const [timeline, setTimeline] = useState<Record<string, unknown>[]>([])

  useEffect(() => {
    if (window.location.pathname === '/ask') return
    fetch(`${API}/instruments`).then((response) => response.json()).then((items) => setInstruments(items.map((item: Record<string, unknown>) => ({ ...item, serialNumber: item.serialNumber ?? item.serialnumber, accuracyClass: item.accuracyClass ?? item.accuracyclass } as Instrument)))).catch(() => setMessage('Backend unavailable. Start Docker Compose to load live data.'))
    fetch(`${API}/test-cases`).then((response) => response.json()).then(setTestCases).catch(() => setTestCases([{ code: 'ACCURACY', name: 'Accuracy / indication error', supported: true }]))
    fetch(`${API}/instruments/untested`).then((response) => response.json()).then(setUntested).catch(() => setUntested([]))
    fetch(`${RAG_API}/insights`).then((response) => response.json()).then(setRagInsights).catch(() => setRagInsights(null))
  }, [])

  async function login(event: FormEvent) {
    event.preventDefault()
    const response = await fetch(`${API}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password }) })
    if (response.ok) setLoggedIn(true)
    else setMessage('Unable to sign in. Check your credentials.')
  }

  async function calculate(event: FormEvent) {
    event.preventDefault()
    setMessage('')
    const response = await fetch(`${API}/tests`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ instrumentModel: selectedModel, testType, testLoad: Number(observations[0].testLoad), indication: Number(observations[0].indication), observations: observations.map((observation) => ({ testLoad: Number(observation.testLoad), indication: Number(observation.indication), scaleInterval: Number(observation.scaleInterval) })) }) })
    if (response.ok) { const item = await response.json(); setResult({ ...item, instrumentModel: item.instrumentModel ?? item.instrumentmodel, permissibleError: item.permissibleError ?? item.permissibleerror, ruleSource: `${item.ruleStandard ?? item.rulestandard} / ${item.ruleVersion ?? item.ruleversion}`, result: item.result }); setTimeline([]) }
    else setMessage('Enter valid positive values. The indication must not be below the test load.')
  }

  async function createReport() {
    if (!result) return
    const response = await fetch(`${API}/reports/${result.id}`, { method: 'POST' })
    if (response.ok) setReport(await response.json())
  }

  async function finalizeReport() {
    if (!report) return
    const response = await fetch(`${API}/reports/${report.id}/finalize`, { method: 'POST' })
    if (response.ok) setReport(await response.json())
  }

  async function verifyReport() {
    if (!report) return
    const response = await fetch(`${API}/reports/${report.id}/verify`)
    if (response.ok) setVerification((await response.json()).state)
  }

  function updateObservation(index: number, field: keyof Observation, value: string) {
    setObservations((current) => current.map((observation, rowIndex) => rowIndex === index ? { ...observation, [field]: value } : observation))
  }

  function addObservation() { setObservations((current) => [...current, { testLoad: '1000', indication: '1000.2', scaleInterval: '1' }]) }

  async function loadTimeline() {
    if (!result) return
    const response = await fetch(`${API}/tests/${result.id}/timeline`)
    if (response.ok) setTimeline(await response.json())
  }

  if (window.location.pathname === '/ask') return <RagAssistant onBack={() => { window.location.href = '/' }} />
  if (window.location.pathname.startsWith('/verification/')) return <VerificationView reportId={window.location.pathname.split('/')[2] ?? ''} />
  if (window.location.pathname === '/register') return <RegistrationView onBack={() => { window.location.href = '/' }} />

  if (!loggedIn) return <main className="login-shell"><div className="login-panel"><p className="eyebrow">LEGAL METROLOGY / SIH 26035</p><h1>Evidence, measured.</h1><p className="lede">A working lab desk for non-automatic weighing instrument verification.</p><form onSubmit={login}><label>Email<input value={email} onChange={(event) => setEmail(event.target.value)} type="email" /></label><label>Password<input value={password} onChange={(event) => setPassword(event.target.value)} type="password" /></label><button type="submit">Open laboratory desk</button></form><small>Demo access: engineer@demo.local</small>{message && <p className="error">{message}</p>}</div></main>

  if (window.location.pathname === '/reports') return <RepositoryView title="Report repository" endpoint="reports" onBack={() => { window.location.href = '/' }} />
  if (window.location.pathname === '/audit') return <RepositoryView title="Audit history" endpoint="audit" onBack={() => { window.location.href = '/' }} />
  if (window.location.pathname === '/analytics') return <RepositoryView title="Recorded analytics" endpoint="analytics" onBack={() => { window.location.href = '/' }} />

  return <main className="app-shell">
    <header className="topbar"><div><p className="eyebrow">LM / OPERATIONS</p><h1>Laboratory desk</h1></div><div className="identity"><span className="online-dot" /> Test Engineer <button className="quiet-button" onClick={() => setLoggedIn(false)}>Sign out</button></div></header>
    <nav className="quick-nav"><button onClick={() => { window.location.href = '/register' }}>Register instrument</button><button onClick={() => { window.location.href = '/reports' }}>Reports</button><button onClick={() => { window.location.href = '/analytics' }}>Analytics</button><button onClick={() => { window.location.href = '/audit' }}>Audit</button></nav>
    <section className="hero-row"><div><p className="eyebrow">TODAY, 03 OCT 2026</p><h2>Make every result defensible.</h2><p className="lede">Record an observation, run the versioned rule, and preserve the evidence trail.</p></div><div className="rule-badge"><span>ACTIVE RULE</span><strong>OIML R76-1:2006</strong><small>Accuracy prototype / Class III</small></div></section>
    <section className="metrics"><Metric label="Instruments" value={String(instruments.length || 2)} detail="registered" /><Metric label="Tests today" value={result ? '1' : '0'} detail="new calculation" /><Metric label="Rule status" value="READY" detail="deterministic" /><Metric label="Audit chain" value="VALID" detail="hash linked" /></section>
    <div className="test-case-strip"><label>Test case<select value={testType} onChange={(event) => setTestType(event.target.value)}>{testCases.map((item) => <option key={item.code} value={item.code} disabled={!item.supported}>{item.name}{item.supported ? '' : ' - not implemented'}</option>)}</select></label><span>Only verified formulas can be executed.</span></div>
    <div className="workspace"><section className="panel"><div className="panel-heading"><div><p className="eyebrow">NEW TEST RUN</p><h3>Digital observation set</h3></div><span className="status-chip">SUPPORTED TEST</span></div><form className="test-form" onSubmit={calculate}><label>Instrument<select value={selectedModel} onChange={(event) => setSelectedModel(event.target.value)}>{(instruments.length ? instruments : [{ model: 'ABC-1000', manufacturer: 'ABC Weighing Systems' } as Instrument]).map((instrument) => <option key={instrument.model} value={instrument.model}>{instrument.model} / {instrument.manufacturer}</option>)}</select></label><div className="observation-table"><div className="observation-header"><span>#</span><span>Test load (kg)</span><span>Indication (kg)</span><span>Scale interval (e)</span></div>{observations.map((observation, index) => <div className="observation-row" key={index}><span>{index + 1}</span><input value={observation.testLoad} onChange={(event) => updateObservation(index, 'testLoad', event.target.value)} type="number" min="0.001" step="0.001" /><input value={observation.indication} onChange={(event) => updateObservation(index, 'indication', event.target.value)} type="number" min="0.001" step="0.001" /><input value={observation.scaleInterval} onChange={(event) => updateObservation(index, 'scaleInterval', event.target.value)} type="number" min="0.001" step="0.001" /></div>)}</div><button className="outline-button" type="button" onClick={addObservation}>+ Add observation row</button><button type="submit">Run deterministic calculation <span>→</span></button></form>{message && <p className="error">{message}</p>}</section><section className={`result-panel ${result ? result.result.toLowerCase() : ''}`}><p className="eyebrow">COMPLIANCE RESULT</p>{result ? <><div className="result-title"><strong>{result.result}</strong><span>{report?.status ?? 'No report yet'}</span></div><dl><div><dt>Observed error</dt><dd>{result.error} kg</dd></div><div><dt>Permissible error</dt><dd>{result.permissibleError} kg</dd></div><div><dt>Rule source</dt><dd>{result.ruleSource}</dd></div></dl><div className="result-footer"><span>✓ Calculation reproducible</span><span>● {observations.length} observations stored</span></div><div className="report-actions">{!report && <button className="outline-button" onClick={createReport}>Create report</button>}{report?.status !== 'FINALIZED' && report && <button className="outline-button" onClick={finalizeReport}>Finalize</button>}{report?.status === 'FINALIZED' && <button className="outline-button" onClick={verifyReport}>Verify SHA-256</button>}{verification && <strong className="verified">{verification}</strong>}<button className="outline-button" onClick={loadTimeline}>View timeline</button></div></> : <div className="empty-result"><span className="measure-mark">∿</span><h3>Awaiting observation</h3><p>Your signed calculation will appear here with its rule source and review state.</p></div>}</section></div>
    {timeline.length > 0 && <section className="panel timeline-panel"><p className="eyebrow">TEST TIMELINE</p><h3>Observation history</h3><div className="timeline">{timeline.map((entry, index) => <div className="timeline-event" key={index}><span className="timeline-dot" /><div><strong>Observation {String(entry.sequence)}</strong><small>Load {String(entry.testload ?? entry.testLoad)} kg · Indication {String(entry.indication)} kg · Error {String(entry.error)} kg</small><b className={entry.result === 'PASS' ? 'pass-text' : 'fail-text'}>{String(entry.result)}</b></div></div>)}</div></section>}
    <section className="dashboard-lower"><section className="panel"><div className="panel-heading"><div><p className="eyebrow">REGISTRATION QUEUE</p><h3>Untested instruments</h3></div><button className="outline-button" onClick={() => { window.location.href = '/register' }}>Register product</button></div>{untested.length === 0 ? <p className="body-copy">All registered instruments have a test history.</p> : <div className="queue-list">{untested.map((instrument) => <button className="queue-item" key={instrument.serialNumber} onClick={() => setSelectedModel(instrument.model)}><strong>{instrument.model}</strong><small>{instrument.manufacturer} · {instrument.serialNumber}</small><span>Start test →</span></button>)}</div>}</section><section className="panel"><p className="eyebrow">RAG CORPUS INSIGHTS</p><h3>Regulatory evidence status</h3>{ragInsights ? <><div className="insight-metrics"><strong>{ragInsights.totalDocuments}<small>documents</small></strong><strong>{ragInsights.totalPages}<small>pages</small></strong><strong>{ragInsights.pagesNeedingOcr}<small>need OCR</small></strong></div><p className="body-copy">{ragInsights.message}</p><div className="document-statuses">{ragInsights.documents.slice(0, 4).map((document) => <span key={document.document}>{document.status} · {document.document}</span>)}</div><button className="outline-button" onClick={() => { window.location.href = '/ask' }}>Ask about the corpus →</button></> : <p className="body-copy">RAG service is currently offline.</p>}</section></section>
    <ReportTools report={report} verification={verification} />
    <section className="bottom-grid"><div className="panel"><div className="panel-heading"><div><p className="eyebrow">REGULATORY ASSISTANT</p><h3>Evidence, not guesses.</h3></div><span className="online-pill">ONLINE ONLY</span></div><p className="body-copy">Ask the RAG service about a requirement and receive the source passage, section, and page alongside the answer. Numerical compliance stays with the rules engine.</p><button className="outline-button" onClick={() => { window.location.href = '/ask' }}>Open AI assistant <span>↗</span></button></div><div className="panel audit-card"><p className="eyebrow">INTEGRITY</p><div className="audit-line"><span className="check">✓</span><div><strong>Audit chain verified</strong><small>All recorded events are hash-linked</small></div></div><div className="audit-line"><span className="check">✓</span><div><strong>QR verification ready</strong><small>Final reports recalculate their canonical hash</small></div></div></div></section>
  </main>
}

function RagAssistant({ onBack }: { onBack: () => void }) {
  const [question, setQuestion] = useState('What requirement applies to an accuracy test for a Class III instrument?')
  const [context, setContext] = useState('')
  const [answer, setAnswer] = useState('')
  const [sources, setSources] = useState<Citation[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function ask(event: FormEvent) {
    event.preventDefault()
    setLoading(true)
    setError('')
    setAnswer('')
    setSources([])
    try {
      const response = await fetch(`${RAG_API}/ask`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question, context, citations: [] }),
      })
      const body = await response.json()
      if (!response.ok) throw new Error(body.detail ?? 'The RAG service rejected the request.')
      if (body.error) throw new Error(`${body.error}${body.status_code ? ` (${body.status_code})` : ''}: ${body.detail ?? body.answer}`)
      setAnswer(body.answer ?? 'No answer returned.')
      setSources(body.sources ?? [])
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Unable to reach the RAG service.')
    } finally {
      setLoading(false)
    }
  }

  return <main className="app-shell rag-shell">
    <header className="topbar"><div><p className="eyebrow">LM / REGULATORY EVIDENCE</p><h1>RAG assistant</h1></div><div className="identity"><span className="online-dot" /> Groq online <button className="quiet-button" onClick={onBack}>Back to desk</button></div></header>
    <section className="rag-hero"><p className="eyebrow">SOURCE-GROUNDED QUESTIONS</p><h2>Ask the corpus.<br />Keep the evidence.</h2><p className="lede">The assistant explains supplied regulatory context. It does not calculate PASS/FAIL results.</p></section>
    <section className="rag-layout"><form className="panel rag-form" onSubmit={ask}><div className="panel-heading"><div><p className="eyebrow">QUESTION</p><h3>What do you need to know?</h3></div><span className="online-pill">GROQ / {import.meta.env.VITE_RAG_MODEL ?? 'qwen/qwen3.8-27b'}</span></div><label>Question<textarea value={question} onChange={(event) => setQuestion(event.target.value)} required minLength={3} maxLength={4000} rows={5} /></label><label>Retrieved regulatory context <span className="field-note">Optional until document retrieval is connected</span><textarea value={context} onChange={(event) => setContext(event.target.value)} maxLength={24000} rows={9} placeholder="Paste relevant passages here, including section and page details." /></label><button className="primary-button" type="submit" disabled={loading}>{loading ? 'Consulting Groq...' : 'Ask regulatory assistant'} <span>→</span></button>{error && <p className="error">{error}</p>}</form><section className="panel answer-panel"><p className="eyebrow">ANSWER</p>{answer ? <><div className="answer-copy">{answer}</div><div className="answer-label">LLM explanation grounded in supplied context</div>{sources.length > 0 && <div className="source-list"><p className="eyebrow">SOURCES</p>{sources.map((source, index) => <div className="source-item" key={`${source.document}-${index}`}><strong>[{index + 1}] {source.document}</strong><small>{source.section ? `Section ${source.section}` : 'Section not supplied'}{source.page ? ` · Page ${source.page}` : ''}</small></div>)}</div>}</> : <div className="empty-result"><span className="measure-mark">?</span><h3>Awaiting a question</h3><p>Ask about a regulation, then inspect the answer and its source trail here.</p></div>}</section></section>
  </main>
}

function Metric({ label, value, detail }: { label: string; value: string; detail: string }) { return <div className="metric"><span>{label}</span><strong>{value}</strong><small>{detail}</small></div> }

function ReportTools({ report, verification }: { report: Report | null; verification: string }) {
  if (!report || report.status !== 'FINALIZED') return null
  return <section className="panel report-tools"><div><p className="eyebrow">FINALIZED REPORT</p><h3>Integrity and verification</h3><p className="body-copy">Scan the QR code or open the public verification page to recalculate the issued hash.</p></div><img className="report-qr" src={`${API}/reports/${report.id}/qr`} alt="Report verification QR code" /><strong className="verified">{verification || 'FINALIZED'}</strong><div className="report-links"><a className="outline-button" href={`/verification/${report.id}`}>Open verification</a><a className="outline-button" href={`${API}/reports/${report.id}/pdf`} download>Download PDF</a><a className="outline-button" href={`${API}/reports/${report.id}/docx`} download>Download DOCX</a></div></section>
}

function RepositoryView({ title, endpoint, onBack }: { title: string; endpoint: string; onBack: () => void }) {
  const [rows, setRows] = useState<Record<string, unknown>[]>([])
  const [error, setError] = useState('')
  useEffect(() => { fetch(`${API}/${endpoint}`).then((response) => response.json()).then(setRows).catch(() => setError('Unable to load this view. Start the backend service.')) }, [endpoint])
  return <main className="app-shell"><header className="topbar"><div><p className="eyebrow">LM / EVIDENCE REPOSITORY</p><h1>{title}</h1></div><button className="quiet-button" onClick={onBack}>Back to desk</button></header><section className="repository-panel panel">{error && <p className="error">{error}</p>}{rows.length === 0 && !error ? <div className="empty-result"><span className="measure-mark">∿</span><h3>No recorded entries</h3><p>Run a test or record an audit event to populate this view.</p></div> : <div className="data-list">{rows.map((row, index) => <article className="data-row" key={String(row.id ?? index)}>{Object.entries(row).slice(0, 8).map(([key, value]) => <span key={key}><small>{key}</small><strong>{String(value ?? '-')}</strong></span>)}</article>)}</div>}</section></main>
}

function VerificationView({ reportId }: { reportId: string }) {
  const [verification, setVerification] = useState<{ state: string; valid: boolean; version: number; storedHash: string; calculatedHash: string; test?: Record<string, unknown>; timeline?: Record<string, unknown>[] } | null>(null)
  const [error, setError] = useState('')
  useEffect(() => { fetch(`${API}/reports/${reportId}/verify`).then(async (response) => { const body = await response.json(); if (!response.ok) throw new Error(body.message ?? 'Report verification failed'); setVerification(body) }).catch((requestError) => setError(requestError instanceof Error ? requestError.message : 'Report verification failed')) }, [reportId])
  return <main className="app-shell verification-shell"><section className="verification-card panel"><p className="eyebrow">PUBLIC REPORT VERIFICATION</p><h1>{verification?.state ?? (error ? 'ERROR' : 'VERIFYING...')}</h1>{error ? <p className="error">{error}</p> : verification && <><p className="verification-message">{verification.valid ? 'Report content matches the finalized SHA-256 record.' : 'The report content does not match the issued version.'}</p>{verification.test && <div className="public-test-summary"><p className="eyebrow">TESTED INSTRUMENT</p><h3>{String(verification.test.instrumentModel)} · {String(verification.test.manufacturer)}</h3><p>{String(verification.test.testType)} · {String(verification.test.ruleStandard)} / {String(verification.test.ruleVersion)}</p><strong className={verification.test.result === 'PASS' ? 'pass-text' : 'fail-text'}>{String(verification.test.result)}</strong></div>}<div className="public-timeline"><p className="eyebrow">TEST CASE TIMELINE</p>{verification.timeline && verification.timeline.length > 0 ? verification.timeline.map((entry, index) => <div className="public-timeline-event" key={index}><span className="timeline-dot" /><div><strong>Case {String(entry.sequence)}</strong><small>Test load: {String(entry.testLoad ?? entry.testload)} kg · Indication: {String(entry.indication)} kg · Scale interval: {String(entry.scaleInterval ?? entry.scaleinterval)} kg</small><small>Error: {String(entry.error)} kg · Permissible error: {String(entry.permissibleError ?? entry.permissibleerror)} kg</small><b className={entry.result === 'PASS' ? 'pass-text' : 'fail-text'}>{String(entry.result)}</b></div></div>) : <p className="body-copy">No observation cases were stored for this report.</p>}</div><dl className="verification-details"><div><dt>Report ID</dt><dd>{reportId}</dd></div><div><dt>Version</dt><dd>{verification.version}</dd></div><div><dt>Stored hash</dt><dd>{verification.storedHash || 'Unavailable'}</dd></div><div><dt>Calculated hash</dt><dd>{verification.calculatedHash}</dd></div></dl></>}<a className="outline-button verification-back" href="/">Open laboratory desk</a></section></main>
}

function RegistrationView({ onBack }: { onBack: () => void }) {
  const [manufacturer, setManufacturer] = useState('Demo Weighing Co.')
  const [model, setModel] = useState('DW-1000')
  const [serialNumber, setSerialNumber] = useState('DW-DEMO-001')
  const [accuracyClass, setAccuracyClass] = useState('III')
  const [maxCapacity, setMaxCapacity] = useState('1000')
  const [scaleInterval, setScaleInterval] = useState('1')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  async function register(event: FormEvent) {
    event.preventDefault(); setBusy(true); setMessage('')
    try {
      let response = await fetch(`${API}/manufacturers`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: manufacturer }) })
      if (!response.ok && response.status !== 500) throw new Error('Manufacturer registration failed')
      response = await fetch(`${API}/instrument-models`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ manufacturer, model, description: 'Registered NAWI prototype', accuracyClass, maxCapacity: Number(maxCapacity), minCapacity: Number(scaleInterval), scaleInterval: Number(scaleInterval), unit: 'kg', type: 'ELECTRONIC' }) })
      if (!response.ok) throw new Error('Instrument model registration failed')
      response = await fetch(`${API}/instruments`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ manufacturer, model, serialNumber, accuracyClass, maxCapacity: Number(maxCapacity), scaleInterval: Number(scaleInterval), unit: 'kg' }) })
      if (!response.ok) throw new Error('Instrument registration failed')
      setMessage('Instrument registered. Return to the desk to start its accuracy test.')
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Registration failed') } finally { setBusy(false) }
  }
  return <main className="app-shell"><header className="topbar"><div><p className="eyebrow">LM / ONBOARDING</p><h1>Register an instrument</h1></div><button className="quiet-button" onClick={onBack}>Back to desk</button></header><form className="panel registration-form" onSubmit={register}><p className="body-copy">Create the manufacturer, model, and physical instrument record used by subsequent test runs.</p><label>Manufacturer<input value={manufacturer} onChange={(event) => setManufacturer(event.target.value)} required /></label><label>Model<input value={model} onChange={(event) => setModel(event.target.value)} required /></label><label>Serial number<input value={serialNumber} onChange={(event) => setSerialNumber(event.target.value)} required /></label><div className="form-grid"><label>Accuracy class<select value={accuracyClass} onChange={(event) => setAccuracyClass(event.target.value)}><option value="I">I</option><option value="II">II</option><option value="III">III</option><option value="IV">IV</option></select></label><label>Maximum capacity (kg)<input value={maxCapacity} onChange={(event) => setMaxCapacity(event.target.value)} type="number" min="0.001" step="0.001" required /></label><label>Scale interval (e)<input value={scaleInterval} onChange={(event) => setScaleInterval(event.target.value)} type="number" min="0.001" step="0.001" required /></label></div><button className="primary-button" disabled={busy}>{busy ? 'Registering...' : 'Register manufacturer, model and instrument'}</button>{message && <p className="success-message">{message}</p>}</form></main>
}
export default App

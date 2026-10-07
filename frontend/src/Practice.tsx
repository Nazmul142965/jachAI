import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  api,
  DIFFICULTIES,
  type AnswerRecord,
  type AnswerResult,
  type Difficulty,
  type Host,
  type PracticeQuestion,
  type Usage,
} from './api';

/** Each of the three loads carries its own failure, so one outage never blanks the page. */
interface Slice<T> {
  data: T | null;
  error: string | null;
}
const pending = <T,>(): Slice<T> => ({ data: null, error: null });

function message(error: unknown): string {
  return error instanceof Error ? error.message : 'Something went wrong.';
}

function relative(iso: string): string {
  const seconds = Math.round((Date.now() - Date.parse(iso)) / 1000);
  if (!Number.isFinite(seconds)) return '';
  if (seconds < 60) return 'just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} h ago`;
  return new Date(iso).toLocaleDateString();
}

export default function Practice({ request, onUsageChanged }: Host) {
  const [questions, setQuestions] = useState<Slice<PracticeQuestion[]>>(pending);
  const [usage, setUsage] = useState<Slice<Usage>>(pending);
  const [history, setHistory] = useState<Slice<AnswerRecord[]>>(pending);

  const [filter, setFilter] = useState('');
  const [selectedId, setSelectedId] = useState('');
  const [chosen, setChosen] = useState('');
  const [result, setResult] = useState<AnswerResult | null>(null);

  const [topic, setTopic] = useState('');
  const [brief, setBrief] = useState('');
  const [difficulty, setDifficulty] = useState<Difficulty>('INTERMEDIATE');
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [answering, setAnswering] = useState(false);
  const [answerError, setAnswerError] = useState<string | null>(null);

  // Guards a late response from a load that has been superseded.
  const loadId = useRef(0);

  const load = useCallback(async () => {
    const mine = ++loadId.current;
    const [q, u, h] = await Promise.allSettled([
      api.questions(request),
      api.usage(request),
      api.history(request),
    ]);
    if (mine !== loadId.current) return;

    setQuestions(q.status === 'fulfilled'
      ? { data: q.value, error: null }
      : { data: null, error: message(q.reason) });
    setUsage(u.status === 'fulfilled'
      ? { data: u.value, error: null }
      : { data: null, error: message(u.reason) });
    setHistory(h.status === 'fulfilled'
      ? { data: h.value.content ?? [], error: null }
      : { data: null, error: message(h.reason) });
  }, [request]);

  useEffect(() => { void load(); }, [load]);

  const all = useMemo(() => questions.data ?? [], [questions.data]);

  // Keep the current question if it survives a reload, else fall back to the first.
  useEffect(() => {
    if (!all.length) { setSelectedId(''); return; }
    setSelectedId((current) => (all.some((q) => q.id === current) ? current : all[0]!.id));
  }, [all]);

  const visible = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) return all;
    return all.filter((q) =>
      q.topic.toLowerCase().includes(needle) || q.prompt.toLowerCase().includes(needle));
  }, [all, filter]);

  const question = all.find((q) => q.id === selectedId) ?? null;
  const answered = useMemo(
    () => new Map((history.data ?? []).map((a) => [a.questionId, a])),
    [history.data]);

  useEffect(() => { setChosen(''); setResult(null); setAnswerError(null); }, [selectedId]);

  const canGenerate = !generating && topic.trim().length > 0
    && usage.data !== null && usage.data.generationRemaining > 0;
  const canAnswer = !answering && question !== null && chosen !== ''
    && usage.data !== null && usage.data.remaining > 0;

  async function generate(event: React.FormEvent) {
    event.preventDefault();
    if (!canGenerate) return;
    setGenerating(true);
    setGenerateError(null);
    try {
      const created = await api.generate(request, {
        topic: topic.trim(),
        difficulty,
        ...(brief.trim() ? { brief: brief.trim() } : {}),
      });
      setQuestions((prev) => ({ data: [created, ...(prev.data ?? [])], error: null }));
      setSelectedId(created.id);
      setTopic('');
      setBrief('');
      setUsage({ data: await api.usage(request), error: null });
      onUsageChanged?.();
    } catch (error) {
      setGenerateError(message(error));
      // The allowance is re-read rather than assumed: the only honest way to say
      // whether anything was charged is to ask the server.
      try { setUsage({ data: await api.usage(request), error: null }); } catch { /* keep the old figure */ }
    } finally {
      setGenerating(false);
    }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!canAnswer || !question) return;
    setAnswering(true);
    setAnswerError(null);
    try {
      const outcome = await api.answer(request, { questionId: question.id, optionId: chosen });
      setResult(outcome);
      const [fresh, past] = await Promise.all([api.usage(request), api.history(request)]);
      setUsage({ data: fresh, error: null });
      setHistory({ data: past.content ?? [], error: null });
      onUsageChanged?.();
    } catch (error) {
      setAnswerError(message(error));
    } finally {
      setAnswering(false);
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <p className="eyebrow">Your account · private</p>
          <h1 id="practice-heading">Practice</h1>
          <p className="muted">
            Questions are generated for you alone. Nothing here reaches a workspace,
            a result or a certificate.
          </p>
        </div>
      </div>

      {/* Quota first: it is a real limit, and both actions below depend on it. */}
      <div className="card panel">
        <h2>Today</h2>
        {usage.data && (
          <>
            <p className="muted">
              {usage.data.generated}/{usage.data.generationLimit} AI questions created
              {' '}({usage.data.generationRemaining} left)
              {' · '}
              {usage.data.used}/{usage.data.limit} answers checked
              {' '}({usage.data.remaining} left) today
            </p>
            <div className="practice-meters">
              <Meter label="Questions created"
                     value={usage.data.generated} max={usage.data.generationLimit} />
              <Meter label="Answers checked"
                     value={usage.data.used} max={usage.data.limit} />
            </div>
          </>
        )}
        {usage.error && (
          <p className="empty">
            Today’s allowance could not be checked, so generating and answering are
            paused. {usage.error}
          </p>
        )}
        {!usage.data && !usage.error && (
          <p className="muted" aria-busy="true">Checking your allowance…</p>
        )}
        <button className="button secondary small" type="button" onClick={() => void load()}>
          Reload
        </button>
      </div>

      <div className="card panel">
        <h2>Generate a question</h2>
        <p className="muted">
          Pick a topic and a level. Your allowance is charged only when a question is
          actually produced.
        </p>
        <form className="stack" onSubmit={generate}>
          <div className="form-row">
            <label>Topic
              <input value={topic} maxLength={80} required
                     placeholder="e.g. Spring bean scopes"
                     onChange={(e) => setTopic(e.target.value)} />
            </label>
            <label>Level
              <select value={difficulty}
                      onChange={(e) => setDifficulty(e.target.value as Difficulty)}>
                {DIFFICULTIES.map((d) => (
                  <option key={d.value} value={d.value}>{d.label}</option>
                ))}
              </select>
            </label>
          </div>
          <label>What should it test? <small>(optional)</small>
            <textarea value={brief} rows={2} maxLength={400}
                      placeholder="Narrow it down, for example: when a prototype bean is injected into a singleton"
                      onChange={(e) => setBrief(e.target.value)} />
          </label>
          <div>
            <button className="button primary" type="submit" disabled={!canGenerate}>
              {generating ? 'Generating…' : 'Generate question'}
            </button>
          </div>
        </form>

        {generating && (
          <p className="practice-progress" role="status" aria-live="polite">
            <span className="practice-spinner" aria-hidden="true" />
            Writing a question on {topic.trim() || 'your topic'}. This usually takes
            5–10 seconds.
          </p>
        )}
        {generateError && (
          <p className="empty" role="status">
            No question was produced. {generateError} Your allowance above is the
            server’s current figure, re-read just now.
          </p>
        )}
        {usage.data?.generationRemaining === 0 && (
          <p className="muted">
            You have used today’s {usage.data.generationLimit} generated questions.
            Answering existing ones still works, and the allowance resets at midnight UTC.
          </p>
        )}
      </div>

      <div className="practice-columns">
        <div className="card panel">
          <h2>Answer a question</h2>
          {questions.error && (
            <p className="empty">Your questions could not be loaded. {questions.error}</p>
          )}
          {!questions.error && !all.length && (
            <p className="empty">
              No questions yet. Generate one above and it appears here straight away.
            </p>
          )}

          {question && (
            <form className="stack" onSubmit={submit}>
              <p className="eyebrow">{question.topic}</p>
              <p className="practice-prompt">{question.prompt}</p>
              <fieldset className="practice-options">
                <legend className="muted">Choose one answer</legend>
                {question.options.map((option) => (
                  <label key={option.id}
                         className={`practice-option${chosen === option.id ? ' is-chosen' : ''}`}>
                    <input type="radio" name="practice-option"
                           checked={chosen === option.id}
                           onChange={() => { setResult(null); setChosen(option.id); }} />
                    <span>{option.text}</span>
                  </label>
                ))}
              </fieldset>
              <div>
                <button className="button primary" type="submit" disabled={!canAnswer}>
                  {answering ? 'Checking…' : 'Check answer'}
                </button>
              </div>
            </form>
          )}

          {answerError && <p className="empty" role="status">{answerError}</p>}
          {result && (
            <div className={`result-box ${result.correct ? 'is-correct' : 'is-incorrect'}`}
                 role="status">
              <p><strong>{result.correct ? 'Correct' : 'Not correct'}</strong></p>
              {!result.correct && question && (
                <p className="muted">
                  The answer is{' '}
                  {question.options.find((o) => o.id === result.correctOptionId)?.text
                    ?? result.correctOptionId}.
                </p>
              )}
              {result.explanation && <p className="muted">{result.explanation}</p>}
            </div>
          )}
        </div>

        <aside className="card panel" aria-label="Your practice questions">
          <h2>Your questions</h2>
          <label htmlFor="pilot-question-filter">Find a question</label>
          <input id="pilot-question-filter" type="search" value={filter}
                 placeholder="Search topic or question"
                 onChange={(e) => setFilter(e.target.value)} />
          <div className="item-list">
            {visible.map((q) => {
              const past = answered.get(q.id);
              return (
                <button key={q.id} type="button"
                        className={`practice-pick${q.id === selectedId ? ' is-current' : ''}`}
                        aria-current={q.id === selectedId ? 'true' : undefined}
                        onClick={() => setSelectedId(q.id)}>
                  <span className="practice-pick-topic">{q.topic}</span>
                  <span className="practice-pick-prompt">{q.prompt}</span>
                  {past && (
                    <span className={`pill ${past.correct ? 'ok' : 'warn'}`}>
                      {past.correct ? 'Answered correctly' : 'Answered, review this'}
                    </span>
                  )}
                </button>
              );
            })}
            {!visible.length && all.length > 0 && (
              <p className="empty">No question matches “{filter}”.</p>
            )}
          </div>

          <h3>Recent answers</h3>
          {history.error && (
            <p className="empty">Answer history could not be loaded. {history.error}</p>
          )}
          {history.data?.length === 0 && (
            <p className="empty">No practice answers yet. Choose a question to begin.</p>
          )}
          <div className="item-list">
            {(history.data ?? []).map((a, i) => (
              <p key={`${a.questionId}-${i}`} className="practice-history-row">
                <span className={`pill ${a.correct ? 'ok' : 'warn'}`}>
                  {a.correct ? 'Correct' : 'Review this'}
                </span>
                <span className="muted">
                  {all.find((q) => q.id === a.questionId)?.prompt ?? a.questionId}
                </span>
                <span className="muted">{relative(a.answeredAt)}</span>
              </p>
            ))}
          </div>
        </aside>
      </div>
    </>
  );
}

function Meter({ label, value, max }: { label: string; value: number; max: number }) {
  const percent = max > 0 ? Math.min(100, Math.round((value / max) * 100)) : 0;
  return (
    <div className="practice-meter">
      <span className="muted">{label}</span>
      <div className="bar" role="img" aria-label={`${value} of ${max} used`}>
        <div className="bar-fill" style={{ width: `${percent}%` }} />
      </div>
      <span className="muted">{value} of {max}</span>
    </div>
  );
}

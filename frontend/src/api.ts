// The contract between the host page and the pilot.
//
// The host hands over a `request` function and nothing else. The access token
// lives in a variable inside app.js and never crosses this boundary, so the
// pilot cannot leak it, store it, or get the refresh-on-401 behaviour wrong.
// That is the whole reason the pilot mounts inside the page instead of being a
// second application on its own URL.
export type Request = <T>(path: string, init?: { method?: string; body?: unknown }) => Promise<T>;

export interface Host {
  request: Request;
  /** Tells the host to re-read the quota it shows elsewhere in its own chrome. */
  onUsageChanged?: () => void;
}

// ---------------------------------------------------------------- API shapes
//
// Transcribed from PracticeController, PracticeService and
// PersonalPracticeGenerationService rather than guessed. Doing it the other way
// round first produced a request the server rejected with "Request validation
// failed", which is precisely the failure these types exist to make impossible.
//
// Three things here are easy to get wrong and are worth naming:
//   - difficulty is BEGINNER | INTERMEDIATE | ADVANCED, not EASY | MEDIUM | HARD;
//   - generating takes no question type, and a practice question is always
//     single choice, so there is no multi-select path to build;
//   - answering carries requestId, an idempotency key, so a retried submit
//     cannot be counted against the quota twice.

export type Difficulty = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED';

export interface Option {
  id: string;
  text: string;
}

/** PracticeCatalog.QuestionView — the public projection, with no answer in it. */
export interface PracticeQuestion {
  id: string;
  topic: string;
  prompt: string;
  options: Option[];
}

/** PracticeService.Usage */
export interface Usage {
  utcDay: string;
  used: number;
  limit: number;
  remaining: number;
  generated: number;
  generationLimit: number;
  generationRemaining: number;
}

/** PracticeController.HistoryView */
export interface AnswerRecord {
  questionId: string;
  selectedOptionId: string;
  correctOptionId: string;
  correct: boolean;
  explanation: string;
  answeredAt: string;
}

/** PracticeController.HistoryPage */
export interface HistoryPage {
  content: AnswerRecord[];
  number: number;
  totalPages: number;
  totalElements: number;
}

/** PracticeService.Result, returned when an answer is checked. */
export interface AnswerResult {
  questionId: string;
  selectedOptionId: string;
  correctOptionId: string;
  correct: boolean;
  explanation: string;
  answeredAt: string;
}

export const api = {
  questions: (r: Request) => r<PracticeQuestion[]>('/practice/questions'),
  usage: (r: Request) => r<Usage>('/practice/usage'),
  history: (r: Request) => r<HistoryPage>('/practice/answers/mine?page=0'),

  generate: (r: Request, body: { topic: string; difficulty: Difficulty; brief?: string }) =>
    r<PracticeQuestion>('/practice/questions/generate', { method: 'POST', body }),

  // requestId is the server's idempotency key. A fresh one per submission, so a
  // genuine retry is one answer rather than two against the daily allowance.
  answer: (r: Request, body: { questionId: string; optionId: string }) =>
    r<AnswerResult>('/practice/answers', {
      method: 'POST',
      body: { requestId: crypto.randomUUID(), ...body },
    }),
};

export const DIFFICULTIES: ReadonlyArray<{ value: Difficulty; label: string }> = [
  { value: 'BEGINNER', label: 'Beginner' },
  { value: 'INTERMEDIATE', label: 'Intermediate' },
  { value: 'ADVANCED', label: 'Advanced' },
];

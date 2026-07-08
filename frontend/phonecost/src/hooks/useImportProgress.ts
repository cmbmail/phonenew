import { useState, useRef, useCallback, useEffect } from 'react';
import type { ImportProgress } from '../types/import';

interface UseImportProgressOptions {
  /** Poll interval in ms (default 1000) */
  interval?: number;
  /** Max poll attempts before giving up (default 300 = 5 min at 1s interval) */
  maxAttempts?: number;
  /** Called when import completes successfully */
  onComplete?: (progress: ImportProgress) => void;
  /** Called when import fails */
  onError?: (progress: ImportProgress) => void;
}

interface UseImportProgressReturn {
  /** Current progress data, null if not polling */
  progress: ImportProgress | null;
  /** Whether we are currently polling */
  polling: boolean;
  /** Start polling for a given batchId */
  startPolling: (batchId: number, progressFn: (id: number) => Promise<ImportProgress>) => void;
  /** Stop polling manually */
  stopPolling: () => void;
  /** Percentage 0-100 based on processed/total */
  percent: number;
}

export function useImportProgress(options: UseImportProgressOptions = {}): UseImportProgressReturn {
  const { interval = 1000, maxAttempts = 300, onComplete, onError } = options;
  const [progress, setProgress] = useState<ImportProgress | null>(null);
  const [polling, setPolling] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const attemptsRef = useRef(0);

  const stopPolling = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    setPolling(false);
    attemptsRef.current = 0;
  }, []);

  const startPolling = useCallback((batchId: number, progressFn: (id: number) => Promise<ImportProgress>) => {
    stopPolling();
    setPolling(true);
    setProgress({ status: 'PENDING', total: 0, processed: 0, elapsed_ms: 0, message: '导入已启动...' });
    attemptsRef.current = 0;

    const poll = async () => {
      attemptsRef.current++;
      if (attemptsRef.current > maxAttempts) {
        setProgress(prev => prev ? { ...prev, status: 'FAILED', message: '轮询超时，请刷新页面查看结果' } : null);
        stopPolling();
        return;
      }
      try {
        const p = await progressFn(batchId);
        setProgress(p);

        if (p.status === 'COMPLETED') {
          stopPolling();
          onComplete?.(p);
          return;
        }
        if (p.status === 'FAILED') {
          stopPolling();
          onError?.(p);
          return;
        }
        // Continue polling
        timerRef.current = setTimeout(poll, interval);
      } catch {
        // Network error - retry
        timerRef.current = setTimeout(poll, interval);
      }
    };

    // First poll after a short delay (give server time to start)
    timerRef.current = setTimeout(poll, 500);
  }, [interval, maxAttempts, onComplete, onError, stopPolling]);

  const percent = progress && progress.total > 0
    ? Math.min(Math.round((progress.processed / progress.total) * 100), 100)
    : 0;

  // Cleanup: stop polling when component unmounts
  useEffect(() => {
    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    };
  }, []);

  return { progress, polling, startPolling, stopPolling, percent };
}

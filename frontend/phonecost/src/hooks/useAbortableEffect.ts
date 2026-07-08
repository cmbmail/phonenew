import { useEffect, useRef } from 'react';

/**
 * useAbortableEffect — 自动管理 AbortController 的 useEffect 封装
 *
 * 每次执行时创建新的 AbortController，effect 函数接收 signal；
 * effect 重新执行或组件卸载时自动 abort 上一次的请求。
 *
 * 用法:
 *   useAbortableEffect((signal) => {
 *     apiGet('/foo', undefined, signal).then(setData);
 *   }, [dep]);
 */
export function useAbortableEffect(
  effect: (signal: AbortSignal) => void | (() => void),
  deps: React.DependencyList,
) {
  const controllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    // Abort previous request if any
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;

    const cleanup = effect(controller.signal);

    return () => {
      controller.abort();
      controllerRef.current = null;
      cleanup?.();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- deps 由调用方显式控制
  }, deps);
}

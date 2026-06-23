import { describe, it, expect } from 'vitest';
import { getErrorMessage, isApiError } from './api';

describe('getErrorMessage', () => {
  it('returns fallback for null/undefined', () => {
    expect(getErrorMessage(null, 'fallback')).toBe('fallback');
    expect(getErrorMessage(undefined, 'fallback')).toBe('fallback');
  });

  it('returns fallback for string errors (not objects)', () => {
    // Strings are not objects, so isApiError returns false → fallback
    expect(getErrorMessage('Network error', 'fallback')).toBe('fallback');
  });

  it('returns fallback for Error instances (no response.data.message)', () => {
    // Error instances are objects (isApiError=true), but lack response.data.message → fallback
    const err = new Error('Something went wrong');
    expect(getErrorMessage(err, 'fallback')).toBe('fallback');
  });

  it('extracts message from AxiosError-like object with response.data.message', () => {
    const axiosErr = {
      response: { data: { message: 'Server error' } },
    };
    expect(getErrorMessage(axiosErr, 'fallback')).toBe('Server error');
  });

  it('extracts message from object with nested response.data.message', () => {
    const apiErr = { response: { data: { message: 'API failed', code: 500 } } };
    expect(getErrorMessage(apiErr, 'fallback')).toBe('API failed');
  });

  it('falls back when object has no response property', () => {
    expect(getErrorMessage({ message: 'raw error' }, 'fallback')).toBe('fallback');
  });

  it('falls back for numbers and other primitives', () => {
    expect(getErrorMessage(42, 'fallback')).toBe('fallback');
    expect(getErrorMessage(true, 'fallback')).toBe('fallback');
  });
});

describe('isApiError', () => {
  it('returns true for plain objects', () => {
    expect(isApiError({ message: 'err', code: 400 })).toBe(true);
    expect(isApiError({})).toBe(true);
    expect(isApiError({ response: { data: {} } })).toBe(true);
  });

  it('returns true for Error instances (they are objects)', () => {
    const err = new Error('test');
    expect(isApiError(err)).toBe(true);
  });

  it('returns false for non-objects', () => {
    expect(isApiError(null)).toBe(false);
    expect(isApiError(undefined)).toBe(false);
    expect(isApiError('string')).toBe(false);
    expect(isApiError(123)).toBe(false);
    expect(isApiError(true)).toBe(false);
  });
});

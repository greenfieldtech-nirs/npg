// Guest experience configuration.
// Determines what an unauthenticated visitor sees:
//   'landing' — the public homepage (default)
//   'login'   — straight to the sign-in screen
// Override at build/dev time with VITE_GUEST_PAGE=login
export const guestPage: 'landing' | 'login' =
  (import.meta.env.VITE_GUEST_PAGE as 'landing' | 'login' | undefined) ?? 'landing'

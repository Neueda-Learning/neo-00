// The five top-level destinations, shared by every screen's navigation bar. One list so
// the tab set (TopNav's tabs prop) and the compact link set (BackofficeScreen, whose own
// tabs already switch between its Applications/Services views) never drift apart.
export const DESTINATIONS = [
  { id: 'landing', label: 'Home' },
  { id: 'apply', label: 'Apply' },
  { id: 'signin', label: 'Sign in' },
  { id: 'backoffice', label: 'Operations' },
  { id: 'simulator', label: 'Simulator' },
];

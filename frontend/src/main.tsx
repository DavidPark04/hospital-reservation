import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import './styles.css';
import './medipick-theme.css';

// Text fields can match :focus-visible even when a pointer gives them focus.
document.addEventListener('pointerdown', () => {
  document.documentElement.setAttribute('data-pointer-focus', '');
}, true);
document.addEventListener('keydown', event => {
  if (event.key === 'Tab') document.documentElement.removeAttribute('data-pointer-focus');
}, true);

const queryClient = new QueryClient({defaultOptions:{queries:{retry:1,refetchOnWindowFocus:false,staleTime:30_000},mutations:{retry:false}}});
ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><QueryClientProvider client={queryClient}><App /></QueryClientProvider></React.StrictMode>);

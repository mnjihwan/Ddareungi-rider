import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { queryRetryDelay, shouldRetryQuery } from "./lib/apiEnvelope";
import "./style.css";

// Default retry policy: one retry for ordinary failures (the former per-query `retry: 1`); a 429 is retried at most
// once and only after its Retry-After. Queries that set `retry: false` keep opting out.
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: shouldRetryQuery, retryDelay: queryRetryDelay } },
});
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>,
);

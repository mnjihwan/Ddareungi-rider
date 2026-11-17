import React from "react";
import ReactDOM from "react-dom/client";
import { QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { queryRetryDelay, shouldRetryQuery } from "./lib/apiEnvelope";
import { reportApiError } from "./lib/clientConfig";
import "./style.css";

// Default retry policy: one retry for ordinary failures (the former per-query `retry: 1`); a 429 is retried at most
// once and only after its Retry-After. Queries that set `retry: false` keep opting out.
// Phase 3 A7: any query answered with 503 MAINTENANCE_MODE switches the app to the maintenance screen.
const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: reportApiError }),
  defaultOptions: { queries: { retry: shouldRetryQuery, retryDelay: queryRetryDelay } },
});
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>,
);

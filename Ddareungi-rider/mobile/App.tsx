import { focusManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useEffect } from 'react';
import { AppState, Platform } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { HomeScreen } from './src/screens/home/HomeScreen';
import { queryRetryDelay, shouldRetryQuery } from './src/shared/rider-contract/apiEnvelope';
import { MaintenanceGate } from './src/shared/ui/MaintenanceGate';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: shouldRetryQuery, retryDelay: queryRetryDelay } },
});

export default function App() {
  useEffect(() => {
    if (Platform.OS === 'web') return;
    focusManager.setFocused(AppState.currentState == null || AppState.currentState === 'active');
    const subscription = AppState.addEventListener('change', (state) => focusManager.setFocused(state === 'active'));
    return () => subscription.remove();
  }, []);

  return (
    <QueryClientProvider client={queryClient}>
      <SafeAreaProvider>
        <MaintenanceGate>
          <HomeScreen />
        </MaintenanceGate>
      </SafeAreaProvider>
    </QueryClientProvider>
  );
}

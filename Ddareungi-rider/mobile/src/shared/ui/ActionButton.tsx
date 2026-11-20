import { Pressable, StyleSheet, Text } from 'react-native';

interface ActionButtonProps {
  label: string;
  onPress: () => void;
  primary?: boolean;
  disabled?: boolean;
  selected?: boolean;
}

export function ActionButton({ label, onPress, primary, disabled, selected }: ActionButtonProps) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled, selected }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [styles.button, (primary || selected) && styles.active, (disabled || pressed) && styles.dimmed]}
    >
      <Text style={[styles.label, (primary || selected) && styles.activeLabel]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: { backgroundColor: '#E2F0E8', paddingHorizontal: 12, paddingVertical: 8, minHeight: 36, borderRadius: 9, justifyContent: 'center' },
  active: { backgroundColor: '#086847' },
  dimmed: { opacity: 0.56 },
  label: { color: '#103F2D', fontWeight: '700' },
  activeLabel: { color: '#FFFFFF' },
});

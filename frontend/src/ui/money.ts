/** Formats an amount in its currency; falls back to the code when the browser does not know it. */
export function money(currency: string, amount: number, digits = 2): string {
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency, maximumFractionDigits: digits }).format(amount);
  } catch {
    return `${currency} ${amount}`;
  }
}

export const SIZE_LABEL: Record<string, string> = { SMALL: 'Small', MEDIUM: 'Medium', LARGE: 'Large' };

// onboarding.models.ts
export type OnboardingStatus = 'IN_PROGRESS' | 'COMPLETED' | 'CLOSED_INCOMPLETE';
export type StepStatus = 'COMPLETED' | 'CURRENT' | 'PENDING';
export type WalletStatus = 'PENDING' | 'ACTIVE';
export type NextActionType = 'CONTINUE_STEP' | 'GO_TO_LOW_AMOUNT_PAYMENTS';

export interface OnboardingStep {
  stepId: string;
  order: number;
  label: string;
  required: boolean;
  status: StepStatus;
}

export interface NextAction {
  type: NextActionType;
  target: string;
}

/** Modelo de progreso del contrato (§3.2). */
export interface OnboardingProgress {
  sessionId: string;
  status: OnboardingStatus;
  totalSteps: number;
  currentStepIndex: number; // base 0 o 1 sin confirmar (SP-5): la UI no lo usa.
  currentStepId: string;
  steps: OnboardingStep[];
  version: number;
  walletStatus: WalletStatus;
  nextAction: NextAction;
}

export interface StartOnboardingRequest {
  acquisitionChannel?: string;
}

export interface ApiFieldError {
  field: string;
  message: string;
}

/** Cuerpo de error devuelto por el BFF (según la implementación de Backend). */
export interface ApiErrorBody {
  code?: string;
  message?: string;
  correlationId?: string;
  fieldErrors?: ApiFieldError[];
}

export type ErrorContext = 'start' | 'submit';

/** Error ya traducido, seguro para mostrar al usuario. */
export interface UiError {
  status: number;
  title: string;
  message: string;
  correlationId?: string;
  fieldErrors: ApiFieldError[];
  retryable: boolean;
  requiresLogin: boolean;
  refreshRequired: boolean;
}

/** Definición de un campo de paso (SP-4: el contrato no define los campos). */
export interface StepFieldDefinition {
  name: string;
  label: string;
  type?: 'text' | 'tel' | 'email' | 'date';
  required?: boolean;
  minLength?: number;
  maxLength?: number;
  pattern?: string;
  hint?: string;
  autocomplete?: string;
}
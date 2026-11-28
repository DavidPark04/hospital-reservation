import {LoaderCircle} from 'lucide-react';

type Props = {
  channel: '문자' | '이메일';
  compact: boolean;
  sending: boolean;
  resend: boolean;
  retrySeconds: number;
  disabled: boolean;
  onClick: () => void;
};

export default function VerificationRequestButton({channel, compact, sending, resend, retrySeconds, disabled, onClick}: Props) {
  const action = resend ? '인증번호 다시 받기' : '인증 요청';
  let label = resend ? '다시 받기' : '인증 요청';
  let description = `${channel} ${action}`;
  if (sending) {
    label = '요청 중';
    description = `${channel} 인증번호 요청 중`;
  } else if (retrySeconds > 0) {
    label = `${retrySeconds}초 후`;
    description = `${channel} 인증번호 ${retrySeconds}초 후 다시 요청 가능`;
  }

  const fullLabel = resend
    ? (channel === '문자' ? '인증번호 다시 받기' : '인증번호 다시 보내기')
    : `${channel} 인증번호 받기`;
  return <button className="button secondary small" type="button" onClick={onClick}
    disabled={disabled || retrySeconds > 0} aria-label={compact ? description : undefined} aria-busy={sending}>
    {!compact && sending && <LoaderCircle size={16} className="spin" aria-hidden="true"/>}
    <span>{compact ? label : fullLabel + (retrySeconds > 0 ? ` (${retrySeconds}초)` : '')}</span>
  </button>;
}

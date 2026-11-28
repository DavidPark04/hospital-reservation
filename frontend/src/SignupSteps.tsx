import {useLayoutEffect, useRef} from 'react';
import {Check} from 'lucide-react';

type Props = {step: 1 | 2; first: string; second: string};

export default function SignupSteps({step, first, second}: Props) {
  const current = useRef<HTMLLIElement>(null);
  const previous = useRef(step);
  useLayoutEffect(() => {
    if (previous.current === step) return;
    previous.current = step;
    current.current?.focus({preventScroll: true});
    current.current?.closest('[role="dialog"]')?.scrollTo({top: 0});
  }, [step]);

  return <ol className="signup-steps" aria-label="회원가입 단계">
    {[first, second].map((label, index) => {
      const number = index + 1;
      const active = number === step;
      return <li key={label} className={number < step ? 'is-complete' : undefined}
        aria-current={active ? 'step' : undefined} tabIndex={active ? -1 : undefined} ref={active ? current : undefined}>
        <span className="signup-step-number">{number < step ? <Check size={14} aria-label="완료"/> : number}</span>{label}
      </li>;
    })}
  </ol>;
}

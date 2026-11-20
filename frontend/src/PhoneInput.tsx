import {useLayoutEffect,useReducer,useRef} from 'react';
import type {InputHTMLAttributes} from 'react';
import {deletePhoneSeparator,formatPhone,formatPhoneEdit} from './phoneFormatting';

type Props=Omit<InputHTMLAttributes<HTMLInputElement>,'value'|'onChange'|'onKeyDown'|'type'> & {value:string;onValueChange:(value:string)=>void};

export default function PhoneInput({value,onValueChange,...props}:Props){
  const input=useRef<HTMLInputElement>(null);
  const nextCaret=useRef<number|null>(null);
  const [revision,refresh]=useReducer(value=>value+1,0);
  useLayoutEffect(()=>{
    if(nextCaret.current!==null&&input.current===document.activeElement){
      input.current?.setSelectionRange(nextCaret.current,nextCaret.current);
    }
    nextCaret.current=null;
  },[value,revision]);
  function apply(edit:{value:string;caret:number}){
    nextCaret.current=edit.caret;onValueChange(edit.value);refresh();
  }
  return <input {...props} ref={input} type="tel" inputMode="tel" autoComplete={props.autoComplete||'tel'} maxLength={props.maxLength??20} value={formatPhone(value)}
    onChange={event=>apply(formatPhoneEdit(event.target.value,event.target.selectionStart??event.target.value.length))}
    onKeyDown={event=>{
      if(event.altKey||event.ctrlKey||event.metaKey||event.shiftKey)return;
      const element=event.currentTarget;
      const edit=deletePhoneSeparator(element.value,element.selectionStart??0,element.selectionEnd??0,event.key);
      if(edit){event.preventDefault();apply(edit);}
    }}/>;
}

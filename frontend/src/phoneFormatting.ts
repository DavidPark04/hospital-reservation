export function formatPhone(value:string){
  const digits=value.replace(/\D/g,'');
  const prefix=digits.startsWith('02')?2:3;
  const middle=prefix===2?(digits.length>9?4:3):(/^01|^070/.test(digits)||digits.length>10?4:3);
  if(digits.length<=prefix)return digits;
  if(digits.length<=prefix+middle)return `${digits.slice(0,prefix)}-${digits.slice(prefix)}`;
  return `${digits.slice(0,prefix)}-${digits.slice(prefix,prefix+middle)}-${digits.slice(prefix+middle)}`;
}

function positionAfterDigits(value:string,count:number){
  if(count===0)return 0;
  let found=0;
  for(let index=0;index<value.length;index++){
    if(/[0-9]/.test(value[index])&&++found===count)return index+1;
  }
  return value.length;
}

export function formatPhoneEdit(value:string,caret:number){
  const digitsBefore=value.slice(0,caret).replace(/\D/g,'').length;
  const formatted=formatPhone(value);
  return {value:formatted,caret:positionAfterDigits(formatted,digitsBefore)};
}

// A separator is inserted by formatting, so delete the adjacent digit in one key press.
export function deletePhoneSeparator(value:string,start:number,end:number,key:string){
  if(start!==end)return null;
  if(key==='Backspace'&&start>0&&value[start-1]==='-'){
    return formatPhoneEdit(value.slice(0,start-2)+value.slice(start),start-2);
  }
  if(key==='Delete'&&value[start]==='-'){
    return formatPhoneEdit(value.slice(0,start)+value.slice(start+2),start);
  }
  return null;
}

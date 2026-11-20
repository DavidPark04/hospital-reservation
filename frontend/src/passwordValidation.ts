export function passwordChangeError(currentPassword:string,newPassword:string,confirmation:string){
  if(!currentPassword)return '현재 비밀번호를 입력해 주세요.';
  if(newPassword.length<8||new TextEncoder().encode(newPassword).length>72)return '새 비밀번호는 8자 이상, 72바이트 이하로 입력해 주세요.';
  if(newPassword!==confirmation)return '새 비밀번호가 서로 일치하지 않아요.';
  if(newPassword===currentPassword)return '현재 비밀번호와 다른 새 비밀번호를 입력해 주세요.';
  return '';
}

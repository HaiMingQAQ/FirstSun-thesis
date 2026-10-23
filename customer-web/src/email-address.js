export function normalizeEmail(value){
 const email=typeof value==='string'?value.trim().toLowerCase():'';
 const [local,domain,...rest]=email.split('@');
 if(email.length>254||!local||local.length>64||!domain||rest.length||!/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+$/.test(local)||local.startsWith('.')||local.endsWith('.')||local.includes('..')||!domain.includes('.')||domain.split('.').some(label=>!label||label.length>63||!/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label)))throw Error('请输入有效邮箱');
 return email;
}
export function validEmail(value){try{normalizeEmail(value);return true;}catch{return false;}}

// Raw app-api values: order/cart money stays in yuan; catalog cards still use the upstream cents contract.
export const yuanMoney=value=>value==null||!Number.isFinite(Number(value))?'—':Number(value).toFixed(2);
export const storeRows=(rows,storeId)=>(rows||[]).filter(row=>Number(row.storeId)===Number(storeId));
export const selectedRows=rows=>rows.filter(row=>Number(row.selectedFlag)===1);
export const estimate=rows=>rows.some(row=>(row.memberPrice??row.retailPrice)==null||!Number.isFinite(Number(row.memberPrice??row.retailPrice)))?null:rows.reduce((sum,row)=>sum+Math.round(Number(row.memberPrice??row.retailPrice)*100)*row.qty,0)/100;
export const canCancel=o=>o?.status===0&&o?.payStatus===0;
export const canReceive=o=>o?.orderType===1&&o?.status===3&&o?.payStatus===1;
export function expired(o,now=Date.now()){const value=typeof o?.expireAt==='number'?o.expireAt:new Date(o?.expireAt).getTime();return Number.isFinite(value)&&value<=now;}
export const canMockPay=(o,capability,local)=>local===true&&capability===true&&canCancel(o)&&!expired(o);
export function paymentModeNotice(o,status,local){
 if(!canCancel(o))return '';
 const unpaid='订单仍未支付，正式支付尚未接入。';
 if(expired(o))return '已超过付款截止时间，当前不可模拟支付。'+unpaid;
 if(local!==true)return '当前页面未开放本机模拟支付。'+unpaid;
 if(status==='enabled')return '本机测试模拟支付已开放，不会产生真实付款；金额与状态由后端再次校验。订单仍未支付。';
 if(status==='unavailable')return '无法核实模拟支付能力，当前不可用。'+unpaid;
 if(status==='checking')return '正在核实模拟支付能力，当前不可用。'+unpaid;
 return '本机模拟支付当前未开放。'+unpaid;
}
export const orderState=o=>({'-1':'已取消',0:'待支付',1:'待拣货',2:'拣货中',3:o.orderType===0?'待自提':'待收货',4:'已完成'}[o.status]||'状态待核实');
const stale=()=>Object.assign(new Error('登录账号已变化，请重新加载'),{code:'STALE'});
export function createTradeApi({request,cartApi,orderApi,identity}){
 let revision=0,creation=null;
 const owns=owner=>{const now=identity();return !!owner.token&&owner.token===now.token&&owner.tenant===now.tenant&&owner.epoch===now.epoch;};
 const assertOwner=owner=>{if(!owns(owner))throw stale();};
 const unwrap=r=>{if(r?.code!==0){const error=new Error(r?.msg||'服务返回异常');error.code=r?.code;throw error;}return r.data;};
 const call=async config=>unwrap(await request({...config,custom:{...config.custom,skipRefresh:true}}));
 async function member(operation){const owner=identity();assertOwner(owner);const data=await operation(()=>assertOwner(owner));assertOwner(owner);return data;}
 async function rowAt(storeId,id,check){const rows=unwrap(await cartApi.getCartList());check();const row=storeRows(rows,storeId).find(r=>Number(r.id)===Number(id));if(!row)throw Error('购物车行已变化或不属于当前门店，请刷新');return row;}
 const api={
  reset(){revision++;creation=null;},
  creation(){if(creation&&!owns(creation.owner))creation=null;return creation;},
  beginCheckout(){const c=api.creation();if(c?.pending||c?.unknown)throw Error('上一笔创建结果尚未确认，请先去订单列表核对');creation=null;},
  cart:storeId=>member(async()=>storeRows(unwrap(await cartApi.getCartList()),storeId)),
  add:(storeId,p,qty=1)=>member(async()=>{if(p.rx||p.device)throw Error('本批仅支持普通非处方药购物车');if(!Number.isSafeInteger(qty)||qty<1)throw Error('请输入有效数量');return unwrap(await cartApi.addCart({storeId,drugId:p.id,qty}));}),
  quantity:(storeId,id,qty)=>member(async check=>{if(!Number.isSafeInteger(qty)||qty<1)throw Error('数量至少为1');await rowAt(storeId,id,check);return unwrap(await cartApi.updateCartQty(id,qty));}),
  select:(storeId,id,selected)=>member(async check=>{await rowAt(storeId,id,check);return unwrap(await cartApi.updateCartSelected(id,selected?1:0));}),
  remove:(storeId,id)=>member(async check=>{await rowAt(storeId,id,check);return unwrap(await cartApi.deleteCart(id));}),
  addresses:()=>member(()=>call({url:'/member/address/list'})),
  available:(storeId,ids)=>member(()=>call({url:'/pharmacy/inventory/available',params:{storeId,drugIds:ids.join(',')}})),
  orders:(pageNo,status)=>member(async check=>{const page=unwrap(await orderApi.getOrderPage({pageNo,pageSize:6,status:status===''?undefined:Number(status)}));check();if(!Number.isSafeInteger(page.total)||page.total<0)throw Error('订单分页返回异常');const list=await Promise.all(page.list.map(async order=>({...order,lines:await call({url:'/member/wx-order/line-list',params:{wxOrderId:order.id}})})));check();return{list,total:page.total};}),
  order:id=>member(async()=>unwrap(await orderApi.getOrder(id))),
  cancel:id=>member(async()=>unwrap(await orderApi.cancelOrder(id,'顾客主动取消'))),
  receive:id=>member(async()=>unwrap(await orderApi.confirmReceive(id))),
  probeCapability:async()=>{try{const value=await member(async()=>unwrap(await orderApi.getMockPaymentAvailable()));return{available:value===true,status:value===true?'enabled':value===false?'disabled':'unavailable'};}catch{return{available:false,status:'unavailable'};}},
  capability:async()=>(await api.probeCapability()).available,
  simulate:(id,local)=>member(async check=>{if(local!==true||await api.capability()!==true)throw Error('当前环境未开放测试模拟支付');check();return unwrap(await orderApi.simulatePay(id));}),
  async create({storeId,orderType,addressId,remark}){
   const previous=api.creation();if(previous?.id)throw Error('订单已创建，请查看订单详情');if(previous?.pending||previous?.unknown)throw Error('创建结果尚未确认，请先去订单列表核对');
   const owner=identity(),version=revision;assertOwner(owner);creation={owner,storeId,pending:true,unknown:false,id:null};let dispatched=false;
   try{
    const rows=await api.cart(storeId);assertOwner(owner);if(version!==revision)throw stale();
    const selected=selectedRows(rows);if(!selected.length)throw Object.assign(new Error('请先选择当前门店的商品'),{code:'VALIDATION'});
    if(selected.some(r=>Number(r.isRx)===1))throw Object.assign(new Error('处方药不能走本批普通结算，请取消勾选'),{code:'VALIDATION'});
    if(![0,1].includes(orderType)||orderType===1&&!addressId)throw Object.assign(new Error('请选择收货方式及本人已有地址'),{code:'VALIDATION'});
    // No idempotency key exists: exactly one create call, never retry an uncertain transport result.
    dispatched=true;const result=await orderApi.createOrder({storeId,orderType,addressId:orderType===1?addressId:undefined,prescId:null,remark:remark||'',usePoints:0});assertOwner(owner);if(version!==revision)throw stale();
    const id=unwrap(result);if(!Number.isSafeInteger(id)||id<=0)throw Error('创建返回的订单编号无法确认');creation={owner,storeId,pending:false,unknown:false,id};return id;
   }catch(e){if(version===revision&&owns(owner)){const uncertain=dispatched&&(!e.code||Number(e.code)>=500&&Number(e.code)<600);creation={owner,storeId,pending:false,unknown:uncertain,id:null};}throw e;}
  }
 };
 return api;
}

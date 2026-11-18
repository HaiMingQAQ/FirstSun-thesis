import test from 'node:test';
import assert from 'node:assert/strict';
import {createTradeApi} from '../src/trade-core.js';

function setup(){
 let owner={token:'A',tenant:'901',epoch:1};
 const calls=[];
 const orderApi={
  getMockPaymentAvailable:async()=>({code:0,data:true}),
  simulatePay:async id=>{calls.push(['pay',id]);throw Error('network result unknown');},
  cancelOrder:async id=>{calls.push(['cancel',id]);return{code:0,data:true};},
  getOrder:async id=>({code:0,data:{id,status:0,payStatus:0}}),
 };
 const api=createTradeApi({identity:()=>({...owner}),orderApi});
 return{api,orderApi,calls,switchOwner(){owner={token:'B',tenant:'901',epoch:2};}};
}

test('unknown payment blocks both writes for that order while other orders remain independent',async()=>{
 const h=setup();
 await assert.rejects(h.api.simulate(1,true),/unknown/);
 assert(h.api.paymentUncertain(1));
 await assert.rejects(h.api.simulate(1,true),/付款结果待核实/);
 await assert.rejects(h.api.cancel(1),/付款结果待核实/);
 await h.api.cancel(2);
 assert.deepEqual(h.calls,[['pay',1],['cancel',2]]);
 assert.equal(h.api.paymentUncertain(2),false);
});

test('failed or malformed refresh retains uncertainty without changing the old snapshot',async()=>{
 const h=setup();
 const old=Object.freeze({id:1,status:0,payStatus:0});
 h.orderApi.getOrder=async()=>({code:0,data:old});
 assert.strictEqual(await h.api.order(1),old);
 await assert.rejects(h.api.simulate(1,true));
 h.orderApi.getOrder=async()=>{throw Error('read failed');};
 await assert.rejects(h.api.order(1),/read failed/);
 for(const data of [{id:2,status:0,payStatus:0},{id:1,status:0},null]){
  h.orderApi.getOrder=async()=>({code:0,data});
  await assert.rejects(h.api.order(1),/订单状态未能确认/);
  assert(h.api.paymentUncertain(1));
 }
 assert.deepEqual(old,{id:1,status:0,payStatus:0});
});

for(const paid of [false,true])test(`confirmed ${paid?'paid':'unpaid'} response unlocks only the refreshed order`,async()=>{
 const h=setup();
 await assert.rejects(h.api.simulate(1,true));
 await assert.rejects(h.api.simulate(2,true));
 h.orderApi.getOrder=async id=>({code:0,data:{id,status:paid?1:0,payStatus:paid?1:0}});
 await h.api.order(1);
 assert.equal(h.api.paymentUncertain(1),false);
 assert.equal(h.api.paymentUncertain(2),true);
});

test('successful payment response also waits for refreshed order before enabling writes',async()=>{
 const h=setup();
 h.orderApi.simulatePay=async()=>({code:0,data:true});
 await h.api.simulate(1,true);
 assert(h.api.paymentUncertain(1));
 await assert.rejects(h.api.cancel(1),/待核实/);
 await h.api.order(1);
 assert.equal(h.api.paymentUncertain(1),false);
});

for(const timing of ['before','during'])test(`a read started ${timing} payment cannot unlock a later uncertain result`,async()=>{
 const h=setup();
 let resolveRead,rejectPayment;
 h.orderApi.getOrder=()=>new Promise(resolve=>{resolveRead=resolve;});
 h.orderApi.simulatePay=()=>new Promise((_,reject)=>{rejectPayment=reject;});
 let read,payment;
 if(timing==='before')read=h.api.order(1);
 payment=h.api.simulate(1,true);
 while(!rejectPayment)await Promise.resolve();
 if(timing==='during')read=h.api.order(1);
 rejectPayment(Error('unknown'));
 await assert.rejects(payment,/unknown/);
 resolveRead({code:0,data:{id:1,status:0,payStatus:0}});
 await read;
 assert(h.api.paymentUncertain(1));
 await assert.rejects(h.api.cancel(1),/待核实/);
});

test('late refresh from an old owner cannot clear a new owner payment guard',async()=>{
 const h=setup();
 await assert.rejects(h.api.simulate(1,true));
 let resolve;
 h.orderApi.getOrder=()=>new Promise(done=>{resolve=done;});
 const pending=h.api.order(1);
 h.switchOwner();
 assert.equal(h.api.paymentUncertain(1),false);
 await assert.rejects(h.api.simulate(1,true));
 resolve({code:0,data:{id:1,status:1,payStatus:1}});
 await assert.rejects(pending,/账号已变化/);
 assert(h.api.paymentUncertain(1));
 h.api.reset();
 assert.equal(h.api.paymentUncertain(1),false);
});

test('explicit payment rejection and unavailable capability do not leave an uncertain result',async()=>{
 const h=setup();
 h.orderApi.simulatePay=async()=>({code:400,msg:'明确拒绝'});
 await assert.rejects(h.api.simulate(1,true),/明确拒绝/);
 assert.equal(h.api.paymentUncertain(1),false);
 h.orderApi.getMockPaymentAvailable=async()=>({code:0,data:false});
 await assert.rejects(h.api.simulate(1,true),/未开放/);
 assert.equal(h.api.paymentUncertain(1),false);
});

import request from './request.js';
import {identity} from './session.js';
import CartApi from '@/sheep/api/pharmacy/cart';
import OrderApi from '@/sheep/api/pharmacy/order';
import {createTradeApi} from './trade-core.js';
export default createTradeApi({request,cartApi:CartApi,orderApi:OrderApi,identity});

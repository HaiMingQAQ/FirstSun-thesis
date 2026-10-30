import {createApp,h} from 'vue';
import App from './App.vue';
import {installUni} from './session.js';
import './prototype.css';
import './live.css';
installUni();
const Icon={props:['name'],render(){const n=this.name.split('-').map(s=>s[0].toUpperCase()+s.slice(1)).join(''),node=window.lucide.icons[n]||window.lucide.icons.Circle;return h('svg',{...node[1],'aria-hidden':'true'},node[2].map(([tag,attrs])=>h(tag,attrs)));}};
createApp(App).component('Icon',Icon).mount('#app');

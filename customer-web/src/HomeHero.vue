<script setup>
function showGuide(){
 const guide=document.getElementById('home-guide');
 if(!guide)return;
 guide.focus({preventScroll:true});
 const headerHeight=document.getElementById('header')?.offsetHeight||0;
 window.scrollTo(0,guide.getBoundingClientRect().top+window.scrollY-headerHeight-24);
}
</script>

<template>
 <section class="home-hero" aria-labelledby="home-title">
  <div class="home-hero-main">
   <div class="home-hero-copy">
    <div class="home-kicker"><Icon name="sun"/> FIRSTSUN · CARE FOR EVERY DAY</div>
    <h1 id="home-title">让健康，<br>成为<span>日常。</span></h1>
    <p class="home-description">从一份日常所需，到一份安心守护。<br>在 FirstSun，轻松找到你需要的健康好物。</p>
    <div class="home-actions">
     <a class="start-shopping" href="#catalog">开始选药 <Icon name="arrow-right"/></a>
     <button class="guide-link" type="button" @click="showGuide">了解购药流程 <Icon name="arrow-up-right"/></button>
    </div>
    <p class="home-footnote"><Icon name="shield-check"/>认真对待你的每一份健康所需</p>
   </div>
   <div class="home-art">
    <img src="/assets/firstsun-home-hero.png" width="1254" height="1254" alt="FirstSun 品牌概念包装" fetchpriority="high">
    <div class="home-care"><Icon name="sun"/><div><strong>多一点关心，多一份安心</strong><small>A LITTLE CARE, EVERY DAY.</small></div></div>
    <span class="home-art-label">FIRSTSUN / 概念包装展示</span>
   </div>
  </div>
  <div class="home-assurances">
   <a href="#catalog"><span class="home-assurance-icon"><Icon name="shield-check"/></span><span>选购有依据<small>查看商品信息，核对规格与包装</small></span></a>
   <a href="#rx-submit"><span class="home-assurance-icon"><Icon name="file-check-2"/></span><span>处方与审核<small>上传处方材料，查看审核进度</small></span></a>
   <div><span class="home-assurance-icon"><Icon name="truck"/></span><span>自提与配送<small>服务范围与方式以实际门店为准</small></span></div>
  </div>
 </section>
</template>

<style scoped>
.home-hero{background:#edf5ef;border:1px solid #e2ece3;border-radius:26px;overflow:hidden;color:#243c34}
.home-hero-main{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));min-height:552px}
.home-hero-copy{padding:68px 24px 54px 60px;position:relative;z-index:1}
.home-kicker{display:flex;align-items:center;gap:12px;color:#0e5e3f;font-size:11px;font-weight:600;letter-spacing:2.6px}
.home-kicker svg{width:19px;height:19px;color:#c97b3e}
.home-hero h1{margin:27px 0 0;color:#0a4630;font-size:clamp(42px,4.5vw,64px);line-height:1.3;letter-spacing:-2px;font-weight:600}
.home-hero h1 span{display:inline-block;position:relative}
.home-hero h1 span::after{content:"";position:absolute;bottom:1px;left:3px;width:calc(100% - 16px);height:8px;border-radius:50%;border-bottom:3px solid #d9ae67;transform:rotate(-2deg)}
.home-description{margin:24px 0 29px;color:#52685b;font-size:15px;line-height:1.95;text-wrap:pretty}
.home-actions{display:flex;align-items:center;gap:25px;flex-wrap:wrap}
.start-shopping{display:inline-flex;align-items:center;justify-content:center;gap:28px;min-height:54px;padding:0 25px;background:#0e5e3f;color:#fff;border-radius:8px;font-size:15px;font-weight:500;box-shadow:0 7px 16px #0e5e3f12;transition:background .2s}
.start-shopping:hover{background:#0a4630}
.guide-link{display:inline-flex;align-items:center;gap:7px;padding:0;color:#0e5e3f;font-size:13px;min-height:40px}
.guide-link:hover{text-decoration:underline;text-underline-offset:5px}
.guide-link svg{width:17px;height:17px}
.home-footnote{display:flex;align-items:center;gap:7px;margin:28px 0 0;color:#52685b;font-size:11px}
.home-footnote svg{width:15px;height:15px}
.home-art{position:relative;min-width:0}
.home-art img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;mix-blend-mode:multiply;mask-image:linear-gradient(to right,transparent,#000 13%)}
.home-art-label{position:absolute;right:30px;bottom:24px;color:#415b4c;font-size:9px;letter-spacing:1.8px}
.home-care{position:absolute;left:5px;bottom:72px;display:flex;align-items:center;gap:12px;padding:15px 18px;background:#fffef8;border:1px solid white;border-radius:12px;box-shadow:0 10px 35px #164d4312;transform:rotate(-4deg)}
.home-care svg{width:26px;height:26px;color:#0e5e3f}
.home-care strong,.home-care small{display:block}
.home-care strong{font-size:13px;font-weight:500}
.home-care small{color:#52685b;font-size:9px;letter-spacing:1.1px;margin-top:4px}
.home-assurances{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:24px;margin:0 38px;padding:25px 20px;border-top:1px solid #d6e4d9}
.home-assurances>a,.home-assurances>div{display:flex;align-items:center;gap:13px;font-size:13px}
.home-assurances>a:hover{color:#0e5e3f;text-decoration:underline;text-underline-offset:3px}
.home-assurances small{display:block;margin-top:5px;color:#52685b;font-size:11px;line-height:1.5}
.home-assurance-icon{display:grid;place-items:center;flex:none;width:39px;height:39px;border:1px solid #dae8dc;border-radius:50%;background:#f7fbf8;color:#0e5e3f}
.home-assurance-icon svg{width:20px;height:20px}
.home-hero :is(a,button):focus-visible{outline:2px solid #a85d26;outline-offset:4px}
@media(min-width:1600px){.home-hero-main{min-height:600px}.home-hero-copy{padding-top:80px;padding-left:74px}.home-hero h1{font-size:68px}}
@media(max-width:1100px){.home-hero-main{min-height:505px}.home-hero-copy{padding:48px 16px 42px 36px}.home-hero h1{font-size:48px}.home-description{font-size:13px}.home-actions{gap:16px}.home-assurances{margin:0 25px;padding:22px 10px;gap:12px}.home-care{bottom:54px;padding:12px}}
@media(max-width:760px){
 .home-hero{border-radius:20px}.home-hero-main{grid-template-columns:1fr;min-height:0}
 .home-hero-copy{padding:35px 28px 20px}.home-kicker{font-size:9px;letter-spacing:1.8px}
 .home-hero h1{font-size:clamp(38px,11vw,48px);margin-top:22px;letter-spacing:-1.5px}
 .home-description{margin:21px 0 24px}.home-footnote{margin-top:21px}
 .home-art{height:min(92vw,420px)}.home-art img{object-fit:contain;mask-image:linear-gradient(to bottom,transparent,#000 12%)}
 .home-care{left:24px;bottom:45px}.home-art-label{right:17px;bottom:12px;font-size:8px}
 .home-assurances{grid-template-columns:1fr;margin:0 24px;padding:24px 0;gap:19px}
}
@media(max-width:360px){.home-hero-copy{padding-inline:20px}.start-shopping{padding:0 16px;gap:12px}.home-actions{gap:12px}.guide-link{font-size:12px}}
@media(prefers-reduced-motion:reduce){.start-shopping{transition:none}}
</style>

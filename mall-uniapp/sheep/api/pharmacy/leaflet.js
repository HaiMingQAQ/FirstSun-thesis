import additionalLeaflets from './reviewed-leaflets.json';

// Manufacturer leaflets reviewed against the exact approval, manufacturer and retail specification.
const reviewed = {
  ...additionalLeaflets,
  H20093089: {
    name: '蒙脱石散',
    manufacturer: '湖南华纳大药厂股份有限公司',
    specification: '3g/袋×10袋/盒',
    url: 'https://www.warrant.com.cn/uploadfiles/2022/01/20220119091752864.pdf',
    revised: '2021-03-09',
    sections: [
      { label: '适应症', text: '适用于成人和儿童的急性或慢性腹泻。' },
      { label: '禁忌', text: '对这种药或其中任何成分过敏的人禁止使用。' },
      { label: '儿童及其他需先咨询的人群', text: '儿童用量请问医师或药师，并由成人监护使用。过敏体质者应慎用。已经在用其他药品的人，请在用药前咨询医师或药师。' },
      { label: '注意事项', text: '急性腹泻时注意纠正脱水。儿童急性腹泻用药三天仍未好转，请咨询医师或药师。' }
    ]
  },
  Z53021107: {
    name: '云南白药气雾剂',
    manufacturer: '云南白药集团股份有限公司',
    specification: '50g气雾剂+60g保险液/盒',
    url: 'https://www.yunnanbaiyao.com.cn/temp/1717061819067.pdf',
    revised: '2024-04-02',
    sections: [
      { label: '功能主治', text: '用于跌打损伤所致的瘀肿疼痛，也用于肌肉酸痛和风湿疼痛。' },
      { label: '禁忌', text: '孕妇、酒精过敏或对此药过敏的人禁止使用。皮肤或黏膜有破溃、化脓时也禁止使用。' },
      { label: '需先咨询的人群', text: '儿童、年老体弱者、心脏病或溃疡病患者，以及经期女性，应由医师指导使用。' },
      { label: '注意事项', text: '仅供外用。正在使用其他药品时，先向医师或药师确认相互作用，详见原说明书。' }
    ]
  }
};

function publicLeafletUrl(value) {
  if (typeof value !== 'string' || !value.trim()) return '';
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password ||
        !url.hostname.includes('.') || /^(?:localhost(?:\.|$)|[\d.]+$)/i.test(url.hostname) ||
        /(?:^|\/)(?:admin-api|private)(?:\/|$)/i.test(url.pathname)) return '';
    return url.href;
  } catch { return ''; }
}

export function drugLeaflet(drug) {
  if (Number(drug.isRx) !== 0 || Number(drug.drugType) === 6) return null;
  const code = String(drug.approvalNo || '').replace(/^国药准字/, '').replace(/\s+/g, '').toUpperCase();
  const entry = reviewed[code];
  if (entry && entry.name === drug.genericName?.trim() &&
      entry.manufacturer === drug.manufacturer?.trim() && entry.specification === drug.specification?.trim()) {
    return { url: entry.url, revised: entry.revised, sections: entry.sections, source: entry.source || '厂家公开说明书' };
  }
  const url = publicLeafletUrl(drug.instructionsUrl);
  return url ? { url, sections: [], source: '药品档案提供' } : null;
}

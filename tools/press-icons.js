// Writes the Press launcher icons, themed-icon layer and notification glyph from the mark's three rects
// (same numbers as PressMark in Mark.kt). Run from the repo root: node tools/press-icons.js app/src/main/res
const fs = require('fs');
const res = process.argv[2];
const rects = [
  { x: 4, y: 42, w: 88, h: 12, r: 3, accent: false }, // bar
  { x: 40, y: 22, w: 18, h: 52, r: 4, accent: false }, // plate
  { x: 64, y: 10, w: 20, h: 76, r: 4, accent: true },  // accent
];
const rr = ({ x, y, w, h, r }) =>
  `M${x + r},${y}h${w - 2 * r}a${r},${r} 0,0 1,${r},${r}v${h - 2 * r}a${r},${r} 0,0 1,${-r},${r}h${-(w - 2 * r)}a${r},${r} 0,0 1,${-r},${-r}v${-(h - 2 * r)}a${r},${r} 0,0 1,${r},${-r}z`;

// The farthest point of the mark from the centre of its bounds (48,48) is an accent corner, ~50.6 units
// away once rounded. Scaling that to 31dp keeps the whole mark inside the 66dp (r=33) safe zone with a
// little air, and centring its bounds centres it visually: the bar runs nearly edge to edge.
function vector(size, viewport, scale, colors, comment) {
  const t = viewport / 2 - 48 * scale;
  const paths = rects.map(q => `        <path android:fillColor="${q.accent ? colors.accent : colors.fg}" android:pathData="${rr(q)}" />`).join('\n');
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- ${comment} Generated from the Press mark (PressMark in Mark.kt). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${size}dp" android:height="${size}dp" android:viewportWidth="${viewport}" android:viewportHeight="${viewport}">
    <group android:translateX="${t.toFixed(3)}" android:translateY="${t.toFixed(3)}" android:scaleX="${scale}" android:scaleY="${scale}">
${paths}
    </group>
</vector>
`;
}
const ink = '#0E0D0B', bone = '#EEEAE2', lime = '#CCFF00';
const launch = 31 / 50.6;
fs.writeFileSync(`${res}/drawable/ic_press_fg_ink.xml`, vector(108, 108, +launch.toFixed(4), { fg: ink, accent: ink }, 'Launcher foreground: the whole mark in ink.'));
fs.writeFileSync(`${res}/drawable/ic_press_fg_bone_lime.xml`, vector(108, 108, +launch.toFixed(4), { fg: bone, accent: lime }, 'Launcher foreground for Ink: bar and plate in bone, accent lime.'));
fs.writeFileSync(`${res}/drawable/ic_press_mono.xml`, vector(108, 108, +launch.toFixed(4), { fg: '#FFFFFF', accent: '#FFFFFF' }, 'Themed-icon (monochrome) layer: the whole mark in one colour.'));
// Status-bar glyph: 24dp, white (the system tints it). Scaling the 96 grid straight down puts edges on
// fractional pixels and blurs it, so the same three rects are snapped to whole units of the 24 grid
// (the 96 grid ÷ 4, rounded): crisp at 24dp at 1x, 2x and 3x.
const stat = [
  { x: 1, y: 10, w: 22, h: 3, r: 0.75 },  // bar
  { x: 10, y: 5, w: 5, h: 13, r: 1 },     // plate
  { x: 16, y: 2, w: 5, h: 19, r: 1 },     // accent
];
fs.writeFileSync(`${res}/drawable/ic_stat_press.xml`, `<?xml version="1.0" encoding="utf-8"?>
<!-- Notification small icon: the Press mark as a white silhouette, pixel-snapped to the 24dp grid. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
${stat.map(q => `    <path android:fillColor="#FFFFFF" android:pathData="${rr(q)}" />`).join('\n')}
</vector>
`);

const icons = { lime: 'ink', ink: 'bone_lime', paper: 'ink', ember: 'ink', cobalt: 'ink', foil: 'ink' };
for (const [name, fg] of Object.entries(icons)) {
  const file = name === 'lime' ? 'ic_launcher' : `ic_launcher_${name}`;
  fs.writeFileSync(`${res}/mipmap-anydpi-v26/${file}.xml`, `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_${name}" />
    <foreground android:drawable="@drawable/ic_press_fg_${fg}" />
    <monochrome android:drawable="@drawable/ic_press_mono" />
</adaptive-icon>
`);
}
console.log('ok', launch);

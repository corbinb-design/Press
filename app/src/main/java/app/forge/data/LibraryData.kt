package app.forge.data

/**
 * The built-in exercise library (v1.1), one line per exercise:
 *
 *     id ~ Name ~ Primary muscle ~ EQUIPMENT ~ T ~ secondary, muscles ~ alias, alias ~ cue | cue | cue
 *
 * T is the tracking type: W weight × reps, A assisted, B bodyweight (+ optional weight), D timed,
 * C carry (weight × distance), K cardio. IDs are stored in history and must never change; the first
 * entries of each group are the original library, in its original order, so drafts from Quick build
 * and existing templates read the same.
 */
internal object LibraryData {
    private val TABLE = """
# Chest
bench ~ Bench Press ~ Chest ~ BARBELL ~ W ~ Triceps, Shoulders ~ bp, flat bench, barbell bench ~ Shoulder blades pinned back and down | Lower to mid-chest, elbows about 45° | Drive your feet into the floor
incline_bench ~ Incline Bench Press ~ Chest ~ BARBELL ~ W ~ Shoulders, Triceps ~ incline bp, incline barbell ~ Bench at 30–45° | Touch the upper chest | Wrists stacked over elbows
pause_bench ~ Paused Bench Press ~ Chest ~ BARBELL ~ W ~ Triceps, Shoulders ~ pause bench, paused bp ~ Hold a full second on the chest | Stay tight, don't sink | Press only once it's still
db_bench ~ Dumbbell Bench Press ~ Chest ~ DUMBBELL ~ W ~ Triceps, Shoulders ~ db bench, dumbbell press, db press ~ Lower until you feel a stretch | Dumbbells stacked over the elbows | Press up and slightly together
incline_db ~ Incline Dumbbell Press ~ Chest ~ DUMBBELL ~ W ~ Shoulders, Triceps ~ incline db, incline dumbbell bench ~ Bench at 30–45° | Elbows slightly tucked | Squeeze the chest at the top
db_fly ~ Dumbbell Fly ~ Chest ~ DUMBBELL ~ W ~ Shoulders ~ db flye, chest fly, flyes ~ Soft bend in the elbows throughout | Open until you feel a stretch | Hug a barrel on the way up
cable_fly ~ Cable Crossover ~ Chest ~ CABLE ~ W ~ Shoulders ~ cable flye, crossover, cable fly ~ Step forward with a slight lean | Arms arc, don't press | Hands meet in front of the chest
pec_deck ~ Pec Deck ~ Chest ~ MACHINE ~ W ~ Shoulders ~ machine fly, butterfly, pec fly ~ Handles at chest height | Lead with the elbows | Pause with the hands together
chest_press ~ Machine Chest Press ~ Chest ~ MACHINE ~ W ~ Triceps, Shoulders ~ chest press machine, seated chest press ~ Handles level with mid-chest | Back flat against the pad | Press without locking hard
dips ~ Dips ~ Chest ~ BODYWEIGHT ~ B ~ Triceps, Shoulders ~ chest dip, parallel bar dip, weighted dip ~ Lean forward slightly for chest | Lower until shoulders dip below elbows | Lock out without shrugging
pushup ~ Push-Up ~ Chest ~ BODYWEIGHT ~ B ~ Triceps, Shoulders ~ press up, pushups, push up ~ Body in one straight line | Hands under the shoulders | Chest to a fist from the floor
decline_bench ~ Decline Bench Press ~ Chest ~ BARBELL ~ W ~ Triceps ~ decline bp, decline barbell ~ Hook the legs in before unracking | Lower to the bottom of the chest | Press back over the shoulders
floor_press ~ Floor Press ~ Chest ~ BARBELL ~ W ~ Triceps ~ barbell floor press ~ Pause when the elbows touch the floor | Press without bouncing | Upper back stays tight
smith_bench ~ Smith Machine Bench Press ~ Chest ~ SMITH ~ W ~ Triceps, Shoulders ~ smith bench, smith press ~ Set the bench so the bar meets mid-chest | Shoulder blades back | Control the lowering
smith_incline ~ Smith Machine Incline Press ~ Chest ~ SMITH ~ W ~ Shoulders, Triceps ~ smith incline ~ Bench at 30° | Bar to the upper chest | Lock out without shrugging
smith_decline ~ Smith Machine Decline Press ~ Chest ~ SMITH ~ W ~ Triceps ~ smith decline ~ Bar to the lower chest | Elbows under the bar | Smooth, even tempo
decline_db ~ Decline Dumbbell Press ~ Chest ~ DUMBBELL ~ W ~ Triceps ~ decline db ~ Feet hooked, head lower than hips | Dumbbells over the lower chest | Press in a slight arc
incline_db_fly ~ Incline Dumbbell Fly ~ Chest ~ DUMBBELL ~ W ~ Shoulders ~ incline fly, incline flye ~ Bench at 30° | Elbows soft and fixed | Stretch, then squeeze together
squeeze_press ~ Dumbbell Squeeze Press ~ Chest ~ DUMBBELL ~ W ~ Triceps ~ crush press, hex press ~ Press the dumbbells hard together | Keep them touching all the way | Slow on the way down
low_cable_fly ~ Low-to-High Cable Fly ~ Chest ~ CABLE ~ W ~ Shoulders ~ low cable fly, upward fly ~ Pulleys at the bottom | Scoop up to chin height | Palms face up slightly
high_cable_fly ~ High-to-Low Cable Fly ~ Chest ~ CABLE ~ W ~ Shoulders ~ high cable fly, downward fly ~ Pulleys above the head | Sweep down toward the hips | Chest proud the whole time
cable_chest_press ~ Cable Chest Press ~ Chest ~ CABLE ~ W ~ Triceps, Shoulders ~ standing cable press ~ Staggered stance, slight lean | Press forward and together | Don't let the elbows flare
div_chest_press ~ Diverging Chest Press ~ Chest ~ MACHINE ~ W ~ Triceps, Shoulders ~ iso-lateral chest press, hammer strength chest, diverging press ~ Handles start at mid-chest | Press out along the arc | One arm at a time if you like
incline_machine ~ Incline Machine Press ~ Chest ~ MACHINE ~ W ~ Shoulders, Triceps ~ incline chest press machine ~ Seat so handles meet the upper chest | Back against the pad | Press up and in
assisted_dip ~ Assisted Dip ~ Chest ~ MACHINE ~ A ~ Triceps, Shoulders ~ dip assist, assisted dips, machine assisted dip ~ More assistance makes it easier | Lean slightly forward | Full lockout at the top
incline_pushup ~ Incline Push-Up ~ Chest ~ BODYWEIGHT ~ B ~ Triceps ~ elevated push up ~ Hands on a bench or bar | Body in a straight line | Chest to the edge
decline_pushup ~ Decline Push-Up ~ Chest ~ BODYWEIGHT ~ B ~ Shoulders, Triceps ~ feet elevated push up ~ Feet on a bench | Hips stay level | Lower under control
band_fly ~ Band Chest Fly ~ Chest ~ BANDS ~ W ~ Shoulders ~ banded fly ~ Anchor the band at chest height | Soft elbows | Hands meet in front
landmine_press ~ Landmine Press ~ Chest ~ BARBELL ~ W ~ Shoulders, Triceps ~ landmine, single arm landmine press ~ Hold the bar end at the shoulder | Press up and forward | Brace the trunk
kb_floor_press ~ Kettlebell Floor Press ~ Chest ~ KETTLEBELL ~ W ~ Triceps ~ kettlebell press floor ~ Upper arm rests on the floor | Bell sits on the back of the wrist | Press straight up
svend_press ~ Svend Press ~ Chest ~ OTHER ~ W ~ Shoulders ~ plate press, plate squeeze ~ Squeeze a plate between the palms | Press straight out | Keep squeezing on the way back
# Back
deadlift ~ Deadlift ~ Back ~ BARBELL ~ W ~ Hamstrings, Glutes ~ dl, conventional deadlift, deads ~ Bar over mid-foot | Push the floor away, chest up | Lock out by squeezing the glutes
bb_row ~ Barbell Row ~ Back ~ BARBELL ~ W ~ Biceps ~ bent over row, bor, barbell bent over row ~ Hinge to about 45° | Pull to the lower ribs | Don't stand up to finish
pendlay ~ Pendlay Row ~ Back ~ BARBELL ~ W ~ Biceps ~ pendlay ~ Bar starts on the floor each rep | Back flat, parallel to the floor | Explode to the chest
db_row ~ One-Arm Dumbbell Row ~ Back ~ DUMBBELL ~ W ~ Biceps ~ db row, single arm row, dumbbell row ~ Hand and knee on the bench | Pull the elbow toward the hip | Don't twist the torso
pullup ~ Pull-Up ~ Back ~ BODYWEIGHT ~ B ~ Biceps ~ pull up, pullups, weighted pull up ~ Start from a dead hang | Drive the elbows to the ribs | Chin over the bar
chinup ~ Chin-Up ~ Back ~ BODYWEIGHT ~ B ~ Biceps ~ chin up, chins, underhand pull up ~ Palms face you, shoulder width | Pull the chest to the bar | Lower all the way
lat_pd ~ Lat Pulldown ~ Back ~ CABLE ~ W ~ Biceps ~ pull down, pulldown, lat pull, wide grip pulldown ~ Thighs locked under the pad | Pull the bar to the upper chest | Lead with the elbows
seated_row ~ Seated Cable Row ~ Back ~ CABLE ~ W ~ Biceps ~ cable row, low row, seated row ~ Sit tall, slight knee bend | Pull to the belly button | Don't rock back
tbar ~ T-Bar Row ~ Back ~ MACHINE ~ W ~ Biceps ~ t bar, landmine row ~ Hinge with a flat back | Pull to the chest | Squeeze the shoulder blades
straight_arm ~ Straight-Arm Pulldown ~ Back ~ CABLE ~ W ~ ~ cable pullover, straight arm pulldown, lat prayer ~ Arms straight, soft elbows | Sweep the bar to the thighs | Feel the lats, not the triceps
sumo_dl ~ Sumo Deadlift ~ Back ~ BARBELL ~ W ~ Glutes, Quads ~ sumo, sumo dl ~ Wide stance, toes out | Hands inside the knees | Push the knees out as you pull
trap_bar_dl ~ Trap Bar Deadlift ~ Back ~ BARBELL ~ W ~ Quads, Glutes ~ hex bar deadlift, trap bar ~ Stand in the middle of the bar | Chest up, hips down | Drive through the whole foot
rack_pull ~ Rack Pull ~ Back ~ BARBELL ~ W ~ Traps, Glutes ~ block pull, rack deadlift ~ Bar just below or above the knee | Same set-up as a deadlift | Lock out hard
deficit_dl ~ Deficit Deadlift ~ Back ~ BARBELL ~ W ~ Hamstrings, Glutes ~ deficit deadlift ~ Stand on a 2–5 cm plate | Hips a little lower | Same flat back
snatch_grip_dl ~ Snatch-Grip Deadlift ~ Back ~ BARBELL ~ W ~ Traps, Hamstrings ~ wide grip deadlift ~ Very wide grip | Hips lower than usual | Keep the bar close
chest_db_row ~ Chest-Supported Dumbbell Row ~ Back ~ DUMBBELL ~ W ~ Biceps ~ incline row, seal row dumbbell, chest supported row ~ Chest on an incline bench | Pull elbows back and up | Pause at the top
seal_row ~ Seal Row ~ Back ~ BARBELL ~ W ~ Biceps ~ bench row ~ Lie face down on a high bench | Pull to the bench | No momentum
kroc_row ~ Kroc Row ~ Back ~ DUMBBELL ~ W ~ Biceps, Forearms ~ heavy db row ~ Heavy, higher reps | A little body English is fine | Full stretch at the bottom
kb_row ~ Kettlebell Row ~ Back ~ KETTLEBELL ~ W ~ Biceps ~ kb row ~ Hinge, flat back | Row to the hip | Lower slowly
cs_tbar ~ Chest-Supported T-Bar Row ~ Back ~ MACHINE ~ W ~ Biceps ~ chest supported t bar, t-bar machine, incline t bar row ~ Chest stays on the pad | Pull the elbows past the body | Control the lowering
div_row ~ Diverging Row ~ Back ~ MACHINE ~ W ~ Biceps ~ iso-lateral row, hammer strength row, diverging low row ~ Chest on the pad | Pull along the arc | One side at a time if you like
high_row ~ Machine High Row ~ Back ~ MACHINE ~ W ~ Biceps ~ high row, iso-lateral high row ~ Pull down and back | Elbows track wide | Squeeze at the bottom
machine_row ~ Seated Machine Row ~ Back ~ MACHINE ~ W ~ Biceps ~ machine row ~ Chest on the pad | Pull to the ribs | Shoulder blades together
single_machine_row ~ Single-Arm Machine Row ~ Back ~ MACHINE ~ W ~ Biceps ~ unilateral machine row ~ Brace with the free hand | Pull to the hip | Full stretch forward
smith_row ~ Smith Machine Row ~ Back ~ SMITH ~ W ~ Biceps ~ smith bent over row ~ Hinge to about 45° | Pull to the lower ribs | Pause briefly
meadows_row ~ Meadows Row ~ Back ~ BARBELL ~ W ~ Biceps ~ landmine single arm row ~ Stand side-on to the landmine | Pull the elbow high | Stretch at the bottom
inverted_row ~ Inverted Row ~ Back ~ BODYWEIGHT ~ B ~ Biceps ~ body row, australian pull up ~ Body straight, heels down | Pull the chest to the bar | Lower all the way
band_row ~ Band Row ~ Back ~ BANDS ~ W ~ Biceps ~ banded row ~ Anchor at chest height | Pull to the ribs | Slow return
neutral_pullup ~ Neutral-Grip Pull-Up ~ Back ~ BODYWEIGHT ~ B ~ Biceps ~ hammer grip pull up, parallel grip pull up ~ Palms face each other | Pull the chest up | Full hang each rep
wide_pullup ~ Wide-Grip Pull-Up ~ Back ~ BODYWEIGHT ~ B ~ Biceps ~ wide pull up ~ Hands wider than the shoulders | Elbows drive down and out | Chin over the bar
assisted_pullup ~ Assisted Pull-Up ~ Back ~ MACHINE ~ A ~ Biceps ~ pull up assist, gravitron, band assisted pull up, assisted chin up ~ More assistance makes it easier | Start from a full hang | Chin over the bar, then lower slowly
close_pd ~ Close-Grip Pulldown ~ Back ~ CABLE ~ W ~ Biceps ~ v bar pulldown, close grip pull down ~ V-handle or narrow bar | Pull to the upper chest | Lean back only slightly
div_lat_pd ~ Diverging Lat Pulldown ~ Back ~ MACHINE ~ W ~ Biceps ~ iso-lateral pulldown, hammer strength pulldown, machine pulldown ~ Thighs under the pad | Pull down along the arc | Stretch fully at the top
single_arm_pd ~ Single-Arm Cable Pulldown ~ Back ~ CABLE ~ W ~ Biceps ~ one arm pulldown ~ Kneel or sit under the pulley | Pull the elbow to the hip | Rotate slightly into it
band_pulldown ~ Band Lat Pulldown ~ Back ~ BANDS ~ W ~ Biceps ~ banded pulldown ~ Anchor the band high | Pull elbows to the ribs | Slow return
db_pullover ~ Dumbbell Pullover ~ Back ~ DUMBBELL ~ W ~ Chest ~ pullover ~ Lie across a bench | Lower behind the head with soft elbows | Pull back over the chest
machine_pullover ~ Machine Pullover ~ Back ~ MACHINE ~ W ~ ~ nautilus pullover ~ Elbows on the pads | Pull down in an arc | Stretch at the top
back_ext ~ 45° Back Extension ~ Back ~ BODYWEIGHT ~ B ~ Glutes, Hamstrings ~ hyperextension, hyper, back extension, 45 degree hyper ~ Pad just below the hips | Hinge, don't round | Squeeze the glutes to come up
reverse_hyper ~ Reverse Hyperextension ~ Glutes ~ MACHINE ~ W ~ Hamstrings, Back ~ reverse hyper ~ Hips at the edge of the pad | Swing the legs up with the glutes | Control the way down
# Shoulders
ohp ~ Overhead Press ~ Shoulders ~ BARBELL ~ W ~ Triceps ~ ohp, military press, press, standing press, shoulder press ~ Glutes and abs tight | Bar travels close to the face | Head through at the top
db_shoulder ~ Seated Dumbbell Press ~ Shoulders ~ DUMBBELL ~ W ~ Triceps ~ db shoulder press, dumbbell ohp, seated db press ~ Back against the bench | Dumbbells at ear height to start | Press up, not together
arnold ~ Arnold Press ~ Shoulders ~ DUMBBELL ~ W ~ Triceps ~ arnold ~ Start palms facing you | Rotate as you press | Reverse on the way down
lat_raise ~ Lateral Raise ~ Shoulders ~ DUMBBELL ~ W ~ ~ side raise, lateral, side lateral raise, lat raise ~ Slight forward lean | Lead with the elbows | Stop at shoulder height
cable_lat ~ Cable Lateral Raise ~ Shoulders ~ CABLE ~ W ~ ~ cable side raise ~ Pulley low, cable behind or in front | Raise out to the side | Slow on the way down
rear_fly ~ Rear Delt Fly ~ Shoulders ~ DUMBBELL ~ W ~ Traps ~ reverse fly, bent over fly, rear delt raise ~ Hinge until nearly flat | Arms out wide, slight bend | Don't squeeze the shoulder blades
face_pull ~ Face Pull ~ Shoulders ~ CABLE ~ W ~ Traps ~ facepull, rope face pull ~ Rope at face height | Pull toward the eyes | Rotate the hands back at the end
upright_row ~ Upright Row ~ Shoulders ~ BARBELL ~ W ~ Traps ~ upright ~ Hands about shoulder width | Elbows lead, to shoulder height | Bar stays close
push_press ~ Push Press ~ Shoulders ~ BARBELL ~ W ~ Triceps, Quads ~ push press ~ Short dip at the knees | Drive the bar up with the legs | Finish with a strict lockout
seated_ohp ~ Seated Barbell Press ~ Shoulders ~ BARBELL ~ W ~ Triceps ~ seated military press ~ Back against the pad | Bar from the upper chest | Press straight up
standing_db_press ~ Standing Dumbbell Press ~ Shoulders ~ DUMBBELL ~ W ~ Triceps, Core ~ standing db press ~ Brace the trunk | Press without leaning back | Dumbbells over the shoulders at the top
machine_shoulder ~ Machine Shoulder Press ~ Shoulders ~ MACHINE ~ W ~ Triceps ~ shoulder press machine ~ Handles at shoulder height | Back on the pad | Press without shrugging
div_shoulder ~ Diverging Shoulder Press ~ Shoulders ~ MACHINE ~ W ~ Triceps ~ iso-lateral shoulder press, hammer strength shoulder ~ Seat so handles start at the shoulders | Press up along the arc | Control the lowering
smith_ohp ~ Smith Machine Shoulder Press ~ Shoulders ~ SMITH ~ W ~ Triceps ~ smith ohp, smith shoulder press ~ Bench just in front of the bar | Bar to the chin | Press to lockout
kb_press ~ Kettlebell Press ~ Shoulders ~ KETTLEBELL ~ W ~ Triceps ~ kb press, kettlebell overhead press ~ Bell in the rack position | Press up and slightly out | Lock out with the biceps by the ear
z_press ~ Z Press ~ Shoulders ~ BARBELL ~ W ~ Triceps, Core ~ seated floor press ~ Sit on the floor, legs straight | Stay tall, no lean | Press straight up
landmine_shoulder ~ Landmine Shoulder Press ~ Shoulders ~ BARBELL ~ W ~ Triceps ~ landmine overhead press ~ Half-kneeling or standing | Press up and forward | Ribs down
machine_lat ~ Machine Lateral Raise ~ Shoulders ~ MACHINE ~ W ~ ~ lateral raise machine ~ Pads just above the elbows | Raise to shoulder height | Pause at the top
band_lat ~ Band Lateral Raise ~ Shoulders ~ BANDS ~ W ~ ~ banded lateral raise ~ Stand on the band | Raise out to the side | Slow return
front_raise ~ Front Raise ~ Shoulders ~ DUMBBELL ~ W ~ ~ db front raise ~ Arms nearly straight | Raise to eye level | No swinging
cable_front_raise ~ Cable Front Raise ~ Shoulders ~ CABLE ~ W ~ ~ cable front ~ Low pulley behind you | Raise to eye level | Control down
y_raise ~ Y-Raise ~ Shoulders ~ DUMBBELL ~ W ~ Traps ~ incline y raise ~ Chest on an incline bench | Raise in a Y | Light weight, slow
rev_pec_deck ~ Reverse Pec Deck ~ Shoulders ~ MACHINE ~ W ~ Traps ~ rear delt machine, reverse fly machine, rear delt fly machine ~ Chest on the pad | Sweep the arms back wide | Pause, then return slowly
cable_rear ~ Cable Rear Delt Fly ~ Shoulders ~ CABLE ~ W ~ Traps ~ cable reverse fly ~ Cross the cables at shoulder height | Pull out and back | Arms stay long
band_pull_apart ~ Band Pull-Apart ~ Shoulders ~ BANDS ~ W ~ Traps ~ pull apart ~ Arms straight at shoulder height | Pull the band to the chest | Squeeze, then return slowly
band_face_pull ~ Band Face Pull ~ Shoulders ~ BANDS ~ W ~ Traps ~ banded face pull ~ Anchor at face height | Pull toward the eyes | Hands finish by the ears
cuban_press ~ Cuban Press ~ Shoulders ~ DUMBBELL ~ W ~ Traps ~ cuban rotation ~ Upright row to the elbows | Rotate the forearms up | Press, then reverse
pike_pushup ~ Pike Push-Up ~ Shoulders ~ BODYWEIGHT ~ B ~ Triceps ~ pike press ~ Hips high, head between the arms | Lower the head toward the hands | Press back up
# Traps
bb_shrug ~ Barbell Shrug ~ Traps ~ BARBELL ~ W ~ Forearms ~ shrug, shrugs ~ Shoulders straight up to the ears | Pause at the top | No rolling
db_shrug ~ Dumbbell Shrug ~ Traps ~ DUMBBELL ~ W ~ Forearms ~ db shrug ~ Arms hang straight | Shrug up, hold a second | Lower slowly
machine_shrug ~ Machine Shrug ~ Traps ~ MACHINE ~ W ~ ~ shrug machine ~ Stand tall under the pads | Shoulders to the ears | Pause at the top
# Biceps
bb_curl ~ Barbell Curl ~ Biceps ~ BARBELL ~ W ~ Forearms ~ curl, bicep curl, barbell bicep curl ~ Elbows pinned to the sides | Curl without swinging | Lower all the way
db_curl ~ Dumbbell Curl ~ Biceps ~ DUMBBELL ~ W ~ Forearms ~ db curl, bicep curl ~ Palms forward | Elbows still | Squeeze at the top
hammer ~ Hammer Curl ~ Biceps ~ DUMBBELL ~ W ~ Forearms ~ hammer, neutral curl ~ Palms face each other | Elbows by the sides | Slow on the way down
incline_curl ~ Incline Dumbbell Curl ~ Biceps ~ DUMBBELL ~ W ~ ~ incline curl ~ Bench at 45°, arms hang behind | Curl without lifting the elbows | Full stretch at the bottom
preacher ~ Preacher Curl ~ Biceps ~ MACHINE ~ W ~ ~ preacher, scott curl ~ Armpits snug to the pad | Don't lock out hard at the bottom | Squeeze at the top
cable_curl ~ Cable Curl ~ Biceps ~ CABLE ~ W ~ Forearms ~ cable bicep curl ~ Low pulley, step back | Elbows fixed | Constant tension
ez_curl ~ EZ-Bar Curl ~ Biceps ~ BARBELL ~ W ~ Forearms ~ ez curl, ez bar curl ~ Grip the angled part of the bar | Elbows by the sides | Full range
concentration ~ Concentration Curl ~ Biceps ~ DUMBBELL ~ W ~ ~ concentration ~ Elbow braced on the inner thigh | Curl to the shoulder | Lower slowly
spider_curl ~ Spider Curl ~ Biceps ~ DUMBBELL ~ W ~ ~ spider ~ Chest on an incline bench | Arms hang straight down | Curl without swinging
bayesian ~ Bayesian Cable Curl ~ Biceps ~ CABLE ~ W ~ ~ behind the body curl, bayesian curl ~ Face away from the pulley | Arm starts behind the body | Curl forward and up
machine_curl ~ Machine Curl ~ Biceps ~ MACHINE ~ W ~ ~ bicep machine ~ Elbows lined up with the pivot | Curl smoothly | Control the return
band_curl ~ Band Curl ~ Biceps ~ BANDS ~ W ~ ~ banded curl ~ Stand on the band | Elbows by the sides | Squeeze at the top
reverse_curl ~ Reverse Curl ~ Biceps ~ BARBELL ~ W ~ Forearms ~ overhand curl, reverse grip curl ~ Palms face down | Wrists straight | Elbows still
cable_hammer ~ Cable Rope Hammer Curl ~ Biceps ~ CABLE ~ W ~ Forearms ~ rope curl, rope hammer curl ~ Rope on a low pulley | Thumbs up | Elbows fixed
zottman ~ Zottman Curl ~ Biceps ~ DUMBBELL ~ W ~ Forearms ~ zottman ~ Curl palms up | Turn palms down at the top | Lower slowly
drag_curl ~ Drag Curl ~ Biceps ~ BARBELL ~ W ~ ~ bar drag curl ~ Drag the bar up the body | Elbows travel back | No swinging
ez_preacher ~ EZ-Bar Preacher Curl ~ Biceps ~ BARBELL ~ W ~ ~ ez preacher ~ Arms flat on the pad | Lower until nearly straight | Squeeze at the top
kb_curl ~ Kettlebell Curl ~ Biceps ~ KETTLEBELL ~ W ~ Forearms ~ kb curl ~ Hold the handle, bell below | Curl without swinging | Slow down
# Triceps
pushdown ~ Triceps Pushdown ~ Triceps ~ CABLE ~ W ~ ~ tricep pushdown, pushdown, cable pushdown ~ Elbows pinned to the sides | Push to full lockout | Let it come up to 90°
oh_ext ~ Overhead Cable Extension ~ Triceps ~ CABLE ~ W ~ ~ overhead extension, french press, cable overhead extension ~ Face away from the pulley | Elbows point forward | Full stretch behind the head
skull ~ Skull Crusher ~ Triceps ~ BARBELL ~ W ~ ~ lying triceps extension, ez skull crusher, skullcrusher ~ Upper arms angled slightly back | Lower toward the forehead | Elbows stay in
cgbp ~ Close-Grip Bench Press ~ Triceps ~ BARBELL ~ W ~ Chest, Shoulders ~ cg bench, close grip bench, cgbp ~ Hands just inside shoulder width | Elbows tucked | Touch the lower chest
db_kickback ~ Dumbbell Kickback ~ Triceps ~ DUMBBELL ~ W ~ ~ kickback, triceps kickback ~ Upper arm parallel to the floor | Straighten fully | Elbow stays still
rope_pushdown ~ Rope Pushdown ~ Triceps ~ CABLE ~ W ~ ~ rope, rope tricep pushdown ~ Elbows by the sides | Pull the rope apart at the bottom | Control up
db_oh_ext ~ Overhead Dumbbell Extension ~ Triceps ~ DUMBBELL ~ W ~ ~ db overhead extension, overhead tricep extension ~ Hold one dumbbell with both hands | Lower behind the head | Elbows point up
ez_oh_ext ~ EZ-Bar Overhead Extension ~ Triceps ~ BARBELL ~ W ~ ~ ez french press ~ Seated, back supported | Lower behind the head | Press back up
jm_press ~ JM Press ~ Triceps ~ BARBELL ~ W ~ Chest ~ jm ~ Close grip, elbows forward | Lower toward the chin | Press back up
tate_press ~ Tate Press ~ Triceps ~ DUMBBELL ~ W ~ ~ tate ~ Dumbbells end to end over the chest | Elbows flare out | Lower to the chest, press up
machine_dip ~ Machine Dip ~ Triceps ~ MACHINE ~ W ~ Chest ~ seated dip, dip machine ~ Sit tall | Press down to lockout | Control the way up
tri_ext_machine ~ Triceps Extension Machine ~ Triceps ~ MACHINE ~ W ~ ~ tricep machine ~ Elbows on the pad | Extend fully | Slow return
bench_dip ~ Bench Dip ~ Triceps ~ BODYWEIGHT ~ B ~ Chest ~ bench dips, chair dip ~ Hands on the bench behind you | Lower until elbows reach 90° | Press back up
diamond_pushup ~ Diamond Push-Up ~ Triceps ~ BODYWEIGHT ~ B ~ Chest ~ close grip push up, triangle push up ~ Hands form a diamond | Elbows tucked | Chest to the hands
cable_kickback ~ Cable Kickback ~ Triceps ~ CABLE ~ W ~ ~ cable triceps kickback ~ Hinge forward | Upper arm still | Straighten fully
band_pushdown ~ Band Pushdown ~ Triceps ~ BANDS ~ W ~ ~ banded pushdown ~ Anchor the band high | Elbows by the sides | Push to lockout
kb_oh_ext ~ Kettlebell Overhead Extension ~ Triceps ~ KETTLEBELL ~ W ~ ~ kb overhead extension ~ Hold the bell by the horns | Lower behind the head | Elbows point up
# Forearms
wrist_curl ~ Wrist Curl ~ Forearms ~ DUMBBELL ~ W ~ ~ forearm curl ~ Forearms on the bench | Curl the wrists up | Let the bar roll to the fingers
rev_wrist_curl ~ Reverse Wrist Curl ~ Forearms ~ DUMBBELL ~ W ~ ~ wrist extension ~ Palms down | Lift with the wrists only | Slow down
dead_hang ~ Dead Hang ~ Forearms ~ BODYWEIGHT ~ D ~ Back ~ hang, bar hang ~ Full grip, thumbs around | Shoulders active, not shrugged | Breathe and hold
plate_pinch ~ Plate Pinch ~ Forearms ~ OTHER ~ D ~ ~ pinch grip ~ Pinch two plates smooth side out | Arm by the side | Hold for time
# Quads
squat ~ Back Squat ~ Quads ~ BARBELL ~ W ~ Glutes, Hamstrings ~ squat, back squat, bs, barbell squat ~ Brace before you descend | Knees track over the toes | Drive up through the mid-foot
front_squat ~ Front Squat ~ Quads ~ BARBELL ~ W ~ Glutes, Core ~ front squat, fs ~ Elbows high | Sit straight down | Chest stays up
leg_press ~ Leg Press ~ Quads ~ MACHINE ~ W ~ Glutes ~ lp, 45 leg press ~ Feet shoulder width on the platform | Lower until the hips want to tuck | Don't lock the knees hard
hack ~ Hack Squat ~ Quads ~ MACHINE ~ W ~ Glutes ~ hack ~ Shoulders under the pads | Feet mid-platform | Go deep with control
leg_ext ~ Leg Extension ~ Quads ~ MACHINE ~ W ~ ~ quad extension, leg extensions ~ Knees lined up with the pivot | Kick up, pause | Slow on the way down
bulgarian ~ Bulgarian Split Squat ~ Quads ~ DUMBBELL ~ W ~ Glutes ~ bss, rear foot elevated split squat, rfess ~ Back foot on a bench | Drop the back knee straight down | Front knee over the toes
goblet ~ Goblet Squat ~ Quads ~ KETTLEBELL ~ W ~ Glutes ~ goblet ~ Hold the bell at the chest | Elbows inside the knees | Sit down between the heels
lunge ~ Walking Lunge ~ Quads ~ DUMBBELL ~ W ~ Glutes ~ lunges, walking lunges ~ Long step | Back knee to just above the floor | Push through the front heel
pause_squat ~ Pause Squat ~ Quads ~ BARBELL ~ W ~ Glutes ~ paused squat ~ Hold two seconds at the bottom | Stay braced in the hole | Drive up hard
box_squat ~ Box Squat ~ Quads ~ BARBELL ~ W ~ Glutes, Hamstrings ~ box ~ Sit back to the box | Pause without relaxing | Drive up
safety_bar_squat ~ Safety Bar Squat ~ Quads ~ BARBELL ~ W ~ Glutes ~ ssb squat, safety squat bar ~ Handles in front | Chest up against the bar | Same depth as a back squat
zercher ~ Zercher Squat ~ Quads ~ BARBELL ~ W ~ Glutes, Core ~ zercher ~ Bar in the crook of the elbows | Stay upright | Sit down between the knees
overhead_squat ~ Overhead Squat ~ Quads ~ BARBELL ~ W ~ Shoulders, Core ~ ohs ~ Wide grip, arms locked | Bar over the mid-foot | Squat with an upright torso
smith_squat ~ Smith Machine Squat ~ Quads ~ SMITH ~ W ~ Glutes ~ smith squat ~ Feet slightly in front of the bar | Sit down, not back | Drive up evenly
pendulum ~ Pendulum Squat ~ Quads ~ MACHINE ~ W ~ Glutes ~ pendulum ~ Back flat on the pad | Deep, knees forward | Drive through the whole foot
belt_squat ~ Belt Squat ~ Quads ~ MACHINE ~ W ~ Glutes ~ belt ~ Belt around the hips | Stay upright | Full depth without back loading
v_squat ~ V-Squat Machine ~ Quads ~ MACHINE ~ W ~ Glutes ~ v squat ~ Shoulders under the pads | Feet mid-platform | Control the depth
single_leg_press ~ Single-Leg Press ~ Quads ~ MACHINE ~ W ~ Glutes ~ one leg press ~ One foot centred | Lower with control | Keep the hips down
split_squat ~ Split Squat ~ Quads ~ DUMBBELL ~ W ~ Glutes ~ static lunge ~ Feet hip width, one forward | Drop the back knee | Push through the front foot
reverse_lunge ~ Reverse Lunge ~ Quads ~ DUMBBELL ~ W ~ Glutes ~ back lunge ~ Step back long | Back knee to the floor | Drive up through the front foot
lateral_lunge ~ Lateral Lunge ~ Quads ~ DUMBBELL ~ W ~ Glutes ~ side lunge ~ Step wide to the side | Sit into that hip | Other leg straight
smith_split_squat ~ Smith Machine Split Squat ~ Quads ~ SMITH ~ W ~ Glutes ~ smith lunge ~ Bar on the upper back | Long stance | Drop straight down
step_up ~ Step-Up ~ Quads ~ DUMBBELL ~ W ~ Glutes ~ box step up ~ Whole foot on the box | Drive through the top leg | Don't push off the back foot
kb_front_squat ~ Kettlebell Front Squat ~ Quads ~ KETTLEBELL ~ W ~ Glutes, Core ~ double kb front squat ~ Bells in the rack | Elbows down | Sit straight down
sissy_squat ~ Sissy Squat ~ Quads ~ BODYWEIGHT ~ B ~ ~ sissy ~ Rise onto the toes | Knees travel forward | Lean back in a straight line
bw_squat ~ Bodyweight Squat ~ Quads ~ BODYWEIGHT ~ B ~ Glutes ~ air squat ~ Arms forward for balance | Hips below the knees | Stand tall
pistol ~ Pistol Squat ~ Quads ~ BODYWEIGHT ~ B ~ Glutes ~ pistol, single leg squat ~ Other leg straight out front | Sit down on one leg | Stand without bouncing
jump_squat ~ Jump Squat ~ Quads ~ BODYWEIGHT ~ B ~ Glutes, Calves ~ squat jump ~ Quarter to half squat | Jump explosively | Land softly
wall_sit ~ Wall Sit ~ Quads ~ BODYWEIGHT ~ D ~ ~ wall squat ~ Back flat on the wall | Thighs parallel to the floor | Hold, breathe
cyclist_squat ~ Cyclist Squat ~ Quads ~ BARBELL ~ W ~ ~ heels elevated squat ~ Heels raised on a plate | Narrow stance | Knees far forward
# Hamstrings
rdl ~ Romanian Deadlift ~ Hamstrings ~ BARBELL ~ W ~ Glutes, Back ~ rdl, romanian, romanian dl ~ Soft knees, push the hips back | Bar slides down the thighs | Stop at the hamstring stretch
db_rdl ~ Dumbbell RDL ~ Hamstrings ~ DUMBBELL ~ W ~ Glutes ~ db romanian, dumbbell romanian deadlift ~ Dumbbells close to the legs | Hips back, back flat | Squeeze up
leg_curl ~ Lying Leg Curl ~ Hamstrings ~ MACHINE ~ W ~ Calves ~ hamstring curl, leg curl, prone leg curl ~ Hips pressed into the pad | Curl to the glutes | Lower slowly
seated_curl ~ Seated Leg Curl ~ Hamstrings ~ MACHINE ~ W ~ ~ seated hamstring curl ~ Thigh pad snug | Lean forward slightly | Full squeeze
good_morning ~ Good Morning ~ Hamstrings ~ BARBELL ~ W ~ Back, Glutes ~ gm ~ Bar on the upper back | Hinge with soft knees | Stop when the back wants to round
sl_rdl ~ Single-Leg RDL ~ Hamstrings ~ DUMBBELL ~ W ~ Glutes ~ single leg romanian deadlift, sl rdl ~ Hips stay square | Back leg straight behind | Reach down, stand tall
stiff_leg ~ Stiff-Leg Deadlift ~ Hamstrings ~ BARBELL ~ W ~ Glutes, Back ~ sldl, stiff leg ~ Knees nearly straight | Bar from the floor each rep | Back flat
smith_rdl ~ Smith Machine RDL ~ Hamstrings ~ SMITH ~ W ~ Glutes ~ smith romanian deadlift ~ Stand close to the bar | Hips back | Stop at the stretch
kb_deadlift ~ Kettlebell Deadlift ~ Hamstrings ~ KETTLEBELL ~ W ~ Glutes, Back ~ kb deadlift ~ Bell between the feet | Hinge, flat back | Stand tall
nordic ~ Nordic Curl ~ Hamstrings ~ BODYWEIGHT ~ B ~ ~ nordic hamstring curl, nordics ~ Ankles locked down | Lower as slowly as you can | Push back up with the hands
glute_ham ~ Glute-Ham Raise ~ Hamstrings ~ BODYWEIGHT ~ B ~ Glutes ~ ghr, glute ham ~ Knees just behind the pad | Lower with a straight body | Curl back up with the hamstrings
band_leg_curl ~ Band Leg Curl ~ Hamstrings ~ BANDS ~ W ~ ~ banded hamstring curl ~ Lie face down, band at the ankle | Curl to the glutes | Slow return
standing_leg_curl ~ Standing Leg Curl ~ Hamstrings ~ MACHINE ~ W ~ ~ single leg curl ~ Hips against the pad | Curl the heel up | Lower slowly
# Glutes
hip_thrust ~ Hip Thrust ~ Glutes ~ BARBELL ~ W ~ Hamstrings ~ barbell hip thrust, glute bridge barbell, ht ~ Upper back on the bench edge | Chin tucked, ribs down | Squeeze hard at the top
kb_swing ~ Kettlebell Swing ~ Glutes ~ KETTLEBELL ~ W ~ Hamstrings, Back ~ swing, kb swing, russian swing ~ Hike the bell back between the legs | Snap the hips forward | Arms just guide it
abduction ~ Hip Abduction ~ Glutes ~ MACHINE ~ W ~ ~ abductor, hip abductor machine, outer thigh ~ Sit tall or lean forward | Push the knees out | Slow return
hip_thrust_machine ~ Hip Thrust Machine ~ Glutes ~ MACHINE ~ W ~ Hamstrings ~ glute drive, machine hip thrust ~ Belt across the hips | Drive up to full hip extension | Pause at the top
kickback_machine ~ Glute Kickback Machine ~ Glutes ~ MACHINE ~ W ~ Hamstrings ~ glute kickback, machine kickback ~ Brace on the pads | Push the leg back and up | Don't arch the lower back
cable_glute_kickback ~ Cable Glute Kickback ~ Glutes ~ CABLE ~ W ~ Hamstrings ~ cable kickback glute ~ Ankle strap, slight hinge | Kick back from the hip | Squeeze at the end
glute_bridge ~ Glute Bridge ~ Glutes ~ BODYWEIGHT ~ B ~ Hamstrings ~ bridge, floor bridge ~ Feet flat, knees bent | Drive the hips up | Squeeze, then lower
single_leg_hip_thrust ~ Single-Leg Hip Thrust ~ Glutes ~ BODYWEIGHT ~ B ~ Hamstrings ~ one leg hip thrust ~ Upper back on the bench | Drive through one heel | Hips stay level
frog_pump ~ Frog Pump ~ Glutes ~ BODYWEIGHT ~ B ~ ~ frog pumps ~ Soles of the feet together | Short, fast bridges | Squeeze at the top
cable_pull_through ~ Cable Pull-Through ~ Glutes ~ CABLE ~ W ~ Hamstrings ~ pull through ~ Face away from a low pulley | Hinge, rope between the legs | Snap the hips forward
smith_hip_thrust ~ Smith Machine Hip Thrust ~ Glutes ~ SMITH ~ W ~ Hamstrings ~ smith glute bridge ~ Bar across the hips | Upper back on the bench | Full lockout
adduction ~ Hip Adduction ~ Glutes ~ MACHINE ~ W ~ ~ adductor, inner thigh, hip adductor machine ~ Sit tall | Squeeze the knees together | Slow return
cable_abduction ~ Cable Hip Abduction ~ Glutes ~ CABLE ~ W ~ ~ standing abduction ~ Ankle strap, stand side-on | Lift the leg out | No leaning
band_walk ~ Banded Lateral Walk ~ Glutes ~ BANDS ~ W ~ ~ monster walk, band walk, crab walk ~ Band above the knees | Half squat | Small steps, keep tension
curtsy_lunge ~ Curtsy Lunge ~ Glutes ~ DUMBBELL ~ W ~ Quads ~ curtsy ~ Step back and across | Hips stay square | Push through the front foot
# Calves
standing_calf ~ Standing Calf Raise ~ Calves ~ MACHINE ~ W ~ ~ calf raise, calf raises, standing calf ~ Balls of the feet on the edge | Full stretch at the bottom | Pause at the top
seated_calf ~ Seated Calf Raise ~ Calves ~ MACHINE ~ W ~ ~ seated calf ~ Pad on the lower thighs | Deep stretch | Hold at the top
leg_press_calf ~ Leg Press Calf Raise ~ Calves ~ MACHINE ~ W ~ ~ calf press ~ Balls of the feet on the platform edge | Knees soft but fixed | Full range
smith_calf ~ Smith Machine Calf Raise ~ Calves ~ SMITH ~ W ~ ~ smith calf raise ~ Stand on a step | Bar on the upper back | Up high, down deep
db_calf ~ Single-Leg Dumbbell Calf Raise ~ Calves ~ DUMBBELL ~ W ~ ~ single leg calf raise ~ Hold a support | One foot on a step | Slow and full range
donkey_calf ~ Donkey Calf Raise ~ Calves ~ MACHINE ~ W ~ ~ donkey ~ Hinge forward, hips under the pad | Heels drop deep | Rise high
bw_calf ~ Bodyweight Calf Raise ~ Calves ~ BODYWEIGHT ~ B ~ ~ calf raise bodyweight ~ On a step edge | Pause at the bottom | Rise fully
# Core
plank ~ Plank ~ Core ~ BODYWEIGHT ~ D ~ Shoulders ~ front plank, forearm plank ~ Elbows under the shoulders | Squeeze the glutes, ribs down | Straight line head to heels
hanging_leg ~ Hanging Leg Raise ~ Core ~ BODYWEIGHT ~ B ~ Forearms ~ hanging leg raises, leg raise ~ Hang without swinging | Curl the hips up | Lower slowly
cable_crunch ~ Cable Crunch ~ Core ~ CABLE ~ W ~ ~ kneeling cable crunch, rope crunch ~ Kneel, rope by the head | Curl the ribs to the hips | Hips stay still
ab_wheel ~ Ab Wheel Rollout ~ Core ~ BODYWEIGHT ~ B ~ Shoulders ~ rollout, ab roller ~ Start on the knees | Roll out with a flat back | Pull back with the abs
side_plank ~ Side Plank ~ Core ~ BODYWEIGHT ~ D ~ Glutes ~ side bridge ~ Elbow under the shoulder | Hips high | Straight line
hollow_hold ~ Hollow Hold ~ Core ~ BODYWEIGHT ~ D ~ ~ hollow body ~ Lower back pressed down | Arms and legs long | Hold the shape
l_sit ~ L-Sit ~ Core ~ BODYWEIGHT ~ D ~ Triceps ~ l sit ~ Hands by the hips | Push down, lift the legs | Legs straight and together
copenhagen ~ Copenhagen Plank ~ Core ~ BODYWEIGHT ~ D ~ Glutes ~ copenhagen, adductor plank ~ Top leg on a bench | Lift the hips | Hold in a straight line
knee_raise ~ Hanging Knee Raise ~ Core ~ BODYWEIGHT ~ B ~ ~ knee raises ~ Hang still | Knees to the chest | Lower with control
captains_chair ~ Captain's Chair Leg Raise ~ Core ~ BODYWEIGHT ~ B ~ ~ roman chair, captain chair ~ Back against the pad | Lift the knees high | No swinging
toes_to_bar ~ Toes to Bar ~ Core ~ BODYWEIGHT ~ B ~ Forearms ~ ttb ~ Hang with active shoulders | Lift the toes to the bar | Control the swing
crunch ~ Crunch ~ Core ~ BODYWEIGHT ~ B ~ ~ crunches, sit up ~ Lower back on the floor | Curl the ribs up | Exhale at the top
decline_situp ~ Decline Sit-Up ~ Core ~ BODYWEIGHT ~ B ~ ~ decline crunch ~ Feet hooked | Curl up | Lower slowly
v_up ~ V-Up ~ Core ~ BODYWEIGHT ~ B ~ ~ v ups ~ Arms and legs long | Meet in the middle | Lower with control
russian_twist ~ Russian Twist ~ Core ~ BODYWEIGHT ~ B ~ ~ twists ~ Lean back, chest up | Rotate the shoulders | Each side is a rep
dead_bug ~ Dead Bug ~ Core ~ BODYWEIGHT ~ B ~ ~ deadbug ~ Lower back pressed down | Opposite arm and leg out | Slow and controlled
bird_dog ~ Bird Dog ~ Core ~ BODYWEIGHT ~ B ~ Glutes ~ birddog ~ Hands under the shoulders | Reach opposite arm and leg | Hips stay level
pallof ~ Pallof Press ~ Core ~ CABLE ~ W ~ ~ anti rotation press ~ Stand side-on to the pulley | Press straight out | Don't let it twist you
woodchop ~ Cable Woodchop ~ Core ~ CABLE ~ W ~ Shoulders ~ wood chop ~ Rotate from the hips and trunk | Arms stay long | Control back
ab_machine ~ Ab Crunch Machine ~ Core ~ MACHINE ~ W ~ ~ ab machine, crunch machine ~ Chest against the pads | Curl down | Slow return
landmine_rotation ~ Landmine Rotation ~ Core ~ BARBELL ~ W ~ Shoulders ~ landmine twist ~ Arms straight | Rotate the bar side to side | Hips turn with it
turkish_getup ~ Turkish Get-Up ~ Core ~ KETTLEBELL ~ W ~ Shoulders ~ tgu, get up ~ Eyes on the bell | Arm locked overhead | Move step by step
mountain_climber ~ Mountain Climber ~ Core ~ BODYWEIGHT ~ D ~ Shoulders ~ climbers ~ Hands under the shoulders | Drive the knees in | Hips stay low
# Carries and conditioning
farmers ~ Farmer's Carry ~ Forearms ~ DUMBBELL ~ C ~ Traps, Core ~ farmer walk, farmers walk, farmers carry ~ Stand tall, shoulders down | Short, quick steps | Grip hard
suitcase_carry ~ Suitcase Carry ~ Core ~ DUMBBELL ~ C ~ Forearms ~ one arm carry ~ Weight in one hand | Don't lean | Walk tall
trap_bar_carry ~ Trap Bar Carry ~ Forearms ~ BARBELL ~ C ~ Traps, Core ~ hex bar carry ~ Deadlift it up | Walk tall | Quick, even steps
overhead_carry ~ Overhead Carry ~ Shoulders ~ KETTLEBELL ~ C ~ Core ~ waiter walk ~ Arm locked overhead | Ribs down | Walk slowly
sled_push ~ Sled Push ~ Quads ~ OTHER ~ C ~ Glutes, Calves ~ prowler, prowler push ~ Arms locked or bent, low lean | Drive with short steps | Keep pushing through the finish
sled_pull ~ Sled Pull ~ Hamstrings ~ OTHER ~ C ~ Glutes, Back ~ sled drag, backward sled drag ~ Lean back against the strap | Small backward steps | Stay low
power_clean ~ Power Clean ~ Glutes ~ BARBELL ~ W ~ Hamstrings, Traps, Quads ~ clean, hang clean ~ Bar close to the body | Explode with the hips | Catch high, elbows fast
thruster ~ Thruster ~ Quads ~ BARBELL ~ W ~ Shoulders ~ squat press ~ Front squat to the bottom | Drive up and press | One smooth motion
kb_clean ~ Kettlebell Clean ~ Glutes ~ KETTLEBELL ~ W ~ Shoulders ~ kb clean ~ Hike the bell back | Snap the hips | Bell lands softly in the rack
kb_snatch ~ Kettlebell Snatch ~ Glutes ~ KETTLEBELL ~ W ~ Shoulders ~ kb snatch ~ Hike back | Punch up through the handle | Lock out overhead
box_jump ~ Box Jump ~ Quads ~ BODYWEIGHT ~ B ~ Calves, Glutes ~ box jumps ~ Swing the arms | Land softly, whole foot | Step down
burpee ~ Burpee ~ Other ~ BODYWEIGHT ~ B ~ Chest, Quads ~ burpees ~ Chest to the floor | Feet back under the hips | Jump and clap overhead
battle_ropes ~ Battle Ropes ~ Shoulders ~ OTHER ~ D ~ Core ~ ropes ~ Athletic stance | Fast, even waves | Breathe
# Cardio
treadmill ~ Treadmill ~ Cardio ~ CARDIO ~ K ~ ~ running machine, treadmill run, incline walk ~ Stand tall, don't hold the rails | Land under the hips | Set a small incline
outdoor_run ~ Outdoor Run ~ Cardio ~ CARDIO ~ K ~ ~ run, running, jog, jogging ~ Easy pace you could talk at | Short, quick steps | Relax the shoulders
walk ~ Walk ~ Cardio ~ CARDIO ~ K ~ ~ walking, hike, hiking ~ Brisk, arms swinging | Heel to toe | Stand tall
bike ~ Bike ~ Cardio ~ CARDIO ~ K ~ ~ cycle, cycling, stationary bike, spin ~ Saddle at hip height | Slight bend at the bottom of the stroke | Steady cadence
air_bike ~ Air Bike ~ Cardio ~ CARDIO ~ K ~ ~ assault bike, echo bike, airdyne ~ Push and pull the handles | Drive with the legs | Pace the first minute
rower ~ Rower ~ Cardio ~ CARDIO ~ K ~ ~ row machine, rowing, erg, concept 2 ~ Legs, then body, then arms | Reverse on the way back | Don't rush the recovery
elliptical ~ Elliptical ~ Cardio ~ CARDIO ~ K ~ ~ cross trainer ~ Stand tall | Push through the whole foot | Use the handles
stair ~ Stair Climber ~ Cardio ~ CARDIO ~ K ~ ~ stairmaster, stairs, stepmill ~ Light hands on the rails | Whole foot on each step | Stand upright
ski_erg ~ Ski Erg ~ Cardio ~ CARDIO ~ K ~ ~ skierg, ski machine ~ Hinge at the hips | Pull down with the lats | Snap back up
swim ~ Swim ~ Cardio ~ CARDIO ~ K ~ ~ swimming, laps, pool ~ Long, relaxed strokes | Breathe every few strokes | Steady kick
jump_rope ~ Jump Rope ~ Cardio ~ CARDIO ~ K ~ ~ skipping, skip rope ~ Small hops on the balls of the feet | Turn with the wrists | Elbows close
""".trimIndent()

    private fun tracking(code: String) = when (code) {
        "A" -> Tracking.ASSISTED; "B" -> Tracking.BODYWEIGHT_REPS; "D" -> Tracking.DURATION
        "C" -> Tracking.DISTANCE_WEIGHT; "K" -> Tracking.CARDIO; else -> Tracking.WEIGHT_REPS
    }

    private fun list(s: String, sep: String) = s.split(sep).map { it.trim() }.filter { it.isNotEmpty() }

    val exercises: List<Exercise> = TABLE.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
        val f = line.split("~").map { it.trim() }
        require(f.size == 8) { "Library line needs 8 fields: $line" }
        Exercise(
            id = f[0], name = f[1], muscle = f[2], equipment = Equipment.valueOf(f[3]), tracking = tracking(f[4]),
            secondary = list(f[5], ","), aliases = list(f[6], ","), cues = list(f[7], "|"),
        )
    }.toList()
}

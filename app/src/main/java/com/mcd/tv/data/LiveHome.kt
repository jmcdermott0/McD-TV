package com.mcd.tv.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ======================= Live TV: the default view, "what's on for J" =======================
//
// One tile per network: every HD, SD, 4K, East, West, backup, mirror ("AT&T: AMC RAW", "AMC (Hulu)") and local
// affiliate feed sits behind its network, and the feed is picked at play time. Rows come from the guide: My Teams,
// Live Games (NFL, college football, MLB, NHL and NBA only), History & Docs, Military, Cyber/AI/Tech and Primetime
// Entertainment (curated networks). The rest of the playlist waits behind "All Channels A-Z".
// Classification comes from who a channel is (the network catalog) and what the guide says is on, not from the
// provider's group names.
//
// The web app has the same rules (LH in docs/app/index.html). Both run tools/livehome/expected.json against the
// stand-in fixture (LiveHomeTest here, tools/webtest/livehome-test.mjs there), so a change on one side that is not
// made on the other fails a test. Pure Kotlin, no Android types.

/**
 * Who a channel is. [family]: a folder of separate channels (event channels, a free service, a country).
 * [fold]: the folder its group names ("US| AT&T", "HR| HORSE RACING") for a channel with no network of its own;
 * [LiveHome.groups] uses it only when every feed of that name has one.
 */
class LiveIdent(val key: String, val label: String, val kind: String, val net: LiveHome.Net?, val region: String, val family: Boolean, val fold: LiveFold? = null)

/** A folder named by a channel's group. */
class LiveFold(val key: String, val label: String, val kind: String)

/** One A-Z entry: a network with its feeds (best first), or a family folder. [feeds] index the channel list. */
class LiveGroup(val id: String, var label: String, val kind: String, val net: LiveHome.Net?, val family: Boolean, val feeds: MutableList<Int>)

/** A playlist by network. [region] and [score] are per channel. */
class LiveGroups(val list: List<LiveGroup>, val ident: Array<LiveIdent>, val score: IntArray) {
    /** Channel index -> its entry's position in [list]. */
    val groupOf: IntArray = IntArray(ident.size).also { a -> list.forEachIndexed { gi, g -> for (i in g.feeds) a[i] = gi } }
}

/** One side of a game for [LiveHome.home]. */
class HomeSide(val name: String, val short: String, val nick: String)

/** A scheduled game (ESPN's scoreboard). [league] is a [League] name. */
class HomeGame(
    val league: String,
    val id: String,
    val state: String,
    val start: Long,
    val detail: String,
    val away: HomeSide,
    val home: HomeSide,
    val casts: List<String>,
)

/** A favorite team. [league] is a [League] name. */
class HomeTeam(val league: String, val name: String)

/** One tile. [type] "game", "team" or "net". [feeds] index the channel list, the one to play first. */
class HomeTile(
    val type: String,
    val league: String,
    val group: String,
    val title: String,
    val sub: String,
    val badge: String,
    val live: Boolean,
    val mine: Boolean,
    val start: Long,
    val feeds: IntArray,
    val team: String = "",
)

class HomeRow(val id: String, val title: String, val tiles: List<HomeTile>)

object LiveHome {
    /** id|label|kind|tags|keys. Keys are [netKey]s. The same text as CATALOG in the web app (a test compares them). */
    const val CATALOG = """
abc|ABC|local|prime|ABC
cbs|CBS|local|prime|CBS
nbc|NBC|local|prime|NBC
fox|FOX|local|prime|FOX
cw|The CW|local|prime|CW,THE CW
pbs|PBS|local|docs,tech|PBS
ion|ION|local||ION
amc|AMC|ent|prime|AMC
fx|FX|ent|prime|FX
fxx|FXX|ent|prime|FXX
tnt|TNT|ent|prime|TNT
tbs|TBS|ent|prime|TBS
usa|USA Network|ent|prime|USA
bravo|Bravo|ent|prime|BRAVO
ae|A&E|ent|prime|A&E,A AND E,AE
hgtv|HGTV|ent|prime|HGTV
food|Food Network|ent|prime|FOOD
tlc|TLC|ent|prime|TLC
paramount|Paramount Network|ent|prime|PARAMOUNT,SPIKE
comedy|Comedy Central|ent|prime|COMEDY CENTRAL
syfy|Syfy|ent|prime|SYFY,SCI FI
discovery|Discovery|docs|prime,docs|DISCOVERY
history|History|docs|prime,docs,military|HISTORY
natgeo|National Geographic|docs|prime,docs|NATIONAL GEOGRAPHIC,NAT GEO
hallmark|Hallmark Channel|ent|prime|HALLMARK
lifetime|Lifetime|ent|prime|LIFETIME
freeform|Freeform|ent|prime|FREEFORM
trutv|truTV|ent|prime|TRUTV,TRU
tvland|TV Land|ent|prime|TV LAND
e|E!|ent|prime|E!,E! ENTERTAINMENT
bet|BET|ent|prime|BET
hbo|HBO|movies|prime|HBO
showtime|Showtime|movies|prime|SHOWTIME
starz|Starz|movies|prime|STARZ
own|OWN|ent||OWN
we|WE tv|ent||WE
ifc|IFC|ent||IFC
sundance|Sundance TV|ent||SUNDANCE
magnolia|Magnolia Network|ent||MAGNOLIA,DIY
oxygen|Oxygen|ent||OXYGEN
id|Investigation Discovery|ent||INVESTIGATION DISCOVERY,ID,ID INVESTIGATION DISCOVERY
ahc|American Heroes Channel|docs|docs,military|AMERICAN HEROES,AHC,AMERICA HEROES
mhc|Military History Channel|docs|docs,military|MILITARY HISTORY
smithsonian|Smithsonian Channel|docs|docs,military|SMITHSONIAN
science|Science Channel|docs|docs,tech|SCIENCE,DISCOVERY SCIENCE
natgeowild|Nat Geo Wild|docs|docs|NAT GEO WILD,NATIONAL GEOGRAPHIC WILD
animalplanet|Animal Planet|docs|docs|ANIMAL PLANET
vice|Vice|docs|docs|VICE,VICELAND
curiosity|CuriosityStream|docs|docs,tech|CURIOSITYSTREAM,CURIOSITY
cnn|CNN|news||CNN
foxnews|Fox News|news||FOX NEWS
msnbc|MSNBC|news||MSNBC,MS NOW,NBC MS NOW
cnbc|CNBC|news|tech|CNBC
bloomberg|Bloomberg TV|news|tech|BLOOMBERG
espn|ESPN|sports||ESPN,ESPN USA
espn2|ESPN2|sports||ESPN2,ESPN 2
espnu|ESPNU|sports||ESPNU,ESPN U
secn|SEC Network|sports||SEC
accn|ACC Network|sports||ACC
btn|Big Ten Network|sports||BIG TEN,BTN,BIG 10
cbssn|CBS Sports Network|sports||CBS SPORTS,CBSSN,CBS SPORTS USA
fs1|FS1|sports||FS1,FOX SPORTS 1
fs2|FS2|sports||FS2,FOX SPORTS 2
nfln|NFL Network|sports||NFL,NFL NET,NFLN
redzone|NFL RedZone|sports||NFL REDZONE,REDZONE,NFL RED ZONE,RED ZONE
mlbn|MLB Network|sports||MLB,MLBN,MLB NET
nhln|NHL Network|sports||NHL,NHLN,NHL NET
nbatv|NBA TV|sports||NBA,NBATV
golf|Golf Channel|sports||GOLF
nbcsp|NBC Sports Philadelphia|sports||NBC SPORTS PHILADELPHIA,NBCSP,NBC SPORTS PHILADELFIA,CSN PHILADELPHIA
nbcspp|NBC Sports Philadelphia Plus|sports||NBC SPORTS PHILADELPHIA PLUS,NBCSP+,CSN PHILADELPHIA PLUS
telemundo|Telemundo|local||TELEMUNDO
univision|Univision|local||UNIVISION
mynet|MyNetworkTV|local||MYNETWORKTV
amcplus|AMC+|ent||AMC PLUS,AMC+
amcthrillers|AMC Thrillers|movies||AMC THRILLERS
antenna|Antenna TV|ent||ANTENNA
aspire|Aspire|ent||ASPIRE
awe|AWE|ent||AWE,A WEALTH OF ENTERTAINMENT,A WEALTH OF ENTERTAINMENT AWE
afv|AFV|ent||AFV,AFV FAMILY
abtv|Angels Broadcast Television|ent||ANGELS BROADCAST TELEVISION,ANGELS BROADCAST TELEVISION ABTV
antena3|Antena 3 Internacional|ent||ANTENA 3 INTERNACIONAL
bbcamerica|BBC America|ent||BBC AMERICA
bether|BET Her|ent||BET HER
betgospel|BET Gospel|ent||BET GOSPEL
betjams|BET Jams|music||BET JAMS
betsoul|BET Soul|music||BET SOUL
bigbrother|Big Brother Live Feeds|ent||BIG BROTHER,BIG BROTHER QUAD VIEW
bounce|Bounce TV|ent||BOUNCE
bouncexl|Bounce XL|ent||BOUNCE XL
buzzr|Buzzr|ent||BUZZR
byutv|BYUtv|ent||BYU,BYUTV
catchy|Catchy Comedy|ent||CATCHY COMEDY
charge|Charge!|ent||CHARGE!
circle|Circle|ent||CIRCLE
cleo|Cleo TV|ent||CLEO
cnc3|CNC3 Trinidad|ent||CNC 3 TRINIDAD,CNC3
comedytv|Comedy.TV|ent||COMEDY
comet|Comet|ent||COMET
cooking|Cooking Channel|ent||COOKING
courttv|Court TV|ent||COURT
cozi|Cozi TV|ent||COZI
crimeinv|Crime & Investigation|ent||CRIME & INVESTIGATION
dabl|Dabl|ent||DABL
daystar|Daystar|ent||DAYSTAR
destamerica|Destination America|docs||DESTINATION AMERICA
discfamily|Discovery Family|kids||DISCOVERY FAMILY
disclife|Discovery Life|docs||DISCOVERY LIFE
dogtv|DOGTV|ent||DOG,DOGTV
elrey|El Rey Network|ent||EL REY
ewtn|EWTN|ent||EWTN,EWTN CATHOLIC
fashionone|Fashion One|ent||FASHION ONE
fetv|FETV|ent||FETV
foxsoul|Fox Soul|ent||FOX SOUL
fxm|FXM|movies||FXM,FX MOVIES
fyi|FYI|ent||FYI
gameshowcentral|Game Show Central|ent||GAME SHOW CENTRAL
gametv|Game TV|ent||GAME
gettv|getTV|ent||GET,GETTV
gaf|Great American Family|ent||GREAT AMERICAN FAMILY,GREAT AMERICAN COUNTRY
gafl|Great American Faith & Living|ent||GREAT AMERICAN FAITH & LIVING
grit|Grit|ent||GRIT
gsn|Game Show Network|ent||GSN,GAME SHOW
hallmarkfamily|Hallmark Family|ent||HALLMARK FAMILY,HALLMARK DRAMA
hallmarkmystery|Hallmark Mystery|ent||HALLMARK MYSTERY,HALLMARK MOVIES MYSTERIES,HALLMARK M & M
heroesicons|Heroes & Icons|ent||HEROES & ICONS
hope|Hope Channel|ent||HOPE
hsn|HSN|ent||HSN
infowars|Infowars|news||INFOWARS
blaze|BlazeTV|news||BLAZE,BLAZETV
insp|INSP|ent||INSP
ionmystery|ION Mystery|ent||ION MYSTERY
ionplus|ION Plus|ent||ION PLUS
jewelry|Jewelry TV|ent||JEWELRY
justicecentral|Justice Central|ent||JUSTICE CENTRAL
laff|Laff|ent||LAFF
pasiones|Pasiones|ent||PASIONES,LATIN PASIONES
lawcrime|Law & Crime|ent||LAW & CRIME
lmn|Lifetime Movies|movies||LMN,LIFETIME MOVIE,LIFETIME MOVIES
lrw|Lifetime Real Women|ent||LIFETIME REAL WOMEN
localnow|Local Now|news||LOCAL NOW
logo|Logo|ent||LOGO
mega|Mega TV|ent||MEGA
metv|MeTV|ent||METV,ME
metvtoons|MeTV Toons|kids||METV TOONS
motortrend|MotorTrend|ent||MOTORTREND
mtv|MTV|music||MTV
mtv2|MTV2|music||MTV2,MTV 2
mtvlive|MTV Live|music||MTV LIVE
cmt|CMT|music||CMT
vh1|VH1|music||VH1,VH 1
fuse|Fuse|music||FUSE,FUSE MUSIC
revolt|Revolt|music||REVOLT
axs|AXS TV|music||AXS,AXS TV NOW
ovation|Ovation|ent||OVATION
pixl|PixL|movies||PIXL
pop|Pop TV|ent||POP
qvc|QVC|ent||QVC,QVC 2
shoplc|Shop LC|ent||SHOP LC
rav|Real America's Voice|news||REAL AMERICA S VOICE
reelz|Reelz|ent||REELZ,REELZ FAMUS
retrotv|Retro TV|ent||RETRO
rfdtv|RFD-TV|ent||RFD
tastemade|Tastemade|ent||TASTEMADE
tbn|TBN|ent||TBN
tcm|TCM|movies||TCM,TURNER CLASSIC MOVIES
victory|The Victory Channel|ent||VICTORY,THE VICTORY
travel|Travel Channel|ent||TRAVEL
tvone|TV One|ent||TV ONE
uptv|UPtv|ent||UP,UPTV
starttv|Start TV|ent||START
zeetv|Zee TV|ent||ZEE
sonymovie|Sony Movie Channel|movies||SONY MOVIE
tmc|The Movie Channel|movies||THE MOVIE,TMC
hdnetmovies|HDNet Movies|movies||NET MOVIES,HDNET MOVIES
cinemax|Cinemax|movies||CINEMAX
mgmplus|MGM+|movies||MGM+,MGM,EPIX
cinesony|Cine Sony|movies||CINE SONY
cineclub|Cine Club|movies||CINE CLUB
cinemadinamita|Cinema Dinamita|movies||CINEMA DINAMITA
weather|The Weather Channel|news||THE WEATHER,WEATHER
foxweather|Fox Weather|news||FOX WEATHER
foxbusiness|Fox Business|news||FOX BUSINESS
hln|HLN|news||HLN
abcnews|ABC News Live|news||ABC NEWS,ABC NEWS LIVE,ABC NEWS 2,ABC NEWS INTERNATIONAL
cbsnews|CBS News 24/7|news||CBS NEWS,CBSN
nbcnewsnow|NBC News Now|news||NBC NEWS NOW
newsnation|NewsNation|news||NEWS NATION,NEWSNATION,WGN AMERICA
newsmax|Newsmax|news||NEWSMAX
newsy|Newsy|news||NEWSY,NEWS Y
oan|One America News|news||OAN,ONE AMERICA NEWS,ONE AMERICAN NEWS
necn|NECN|news||NECN,NBC NEW ENGLAND CABLE NEWS,NBC NEW ENGLAND CABLE NEWS NECN
cheddar|Cheddar News|news||CHEDDAR,CHEDDAR NEWS,CHEDDAR BUSINESS
cnbcworld|CNBC World|news||CNBC WORLD
cspan|C-SPAN|news||C SPAN,C SPAN 1
cspan2|C-SPAN2|news||C SPAN 2
cspan3|C-SPAN3|news||C SPAN 3
bbcnews|BBC News|news||BBC NEWS,BBC WORLD NEWS
aljazeera|Al Jazeera English|news||AL JAZEERA,AL JAZEERA ENGLISH
euronews|Euronews|news||EURONEWS
trtworld|TRT World|news||TRT WORLD
rtnews|RT|news||RT,RT NEWS
rtdoc|RT Documentary|docs||RT DOCUMENTARY
thefirst|The First|news||THE FIRST
cartoon|Cartoon Network|kids||CARTOON
adultswim|Adult Swim|kids||ADULT SWIM
boomerang|Boomerang|kids||BOOMERANG
disney|Disney Channel|kids||DISNEY
disneyjr|Disney Junior|kids||DISNEY JR,DISNEY JUNIOR
disneyxd|Disney XD|kids||DISNEY XD
nick|Nickelodeon|kids||NICKELODEON,NICK
nickjr|Nick Jr.|kids||NICK JR
nicktoons|Nicktoons|kids||NICKTOONS,NICK TOONS
teennick|TeenNick|kids||TEENNICK,TEEN NICK
pbskids|PBS Kids|kids||PBS KIDS
babyfirst|BabyFirst|kids||BABYFIRST,BABY FIRST
babytv|BabyTV|kids||BABYTV,BABY
threeabnkids|3ABN Kids|kids||3ABN KIDS
espnews|ESPNews|sports||ESPNEWS,ESPN NEWS
nbcsn|NBCSN|sports||NBC SPORTS,NBCSN
nbcsportsnow|NBC Sports Now|sports||NBC SPORTS NOW
foxdeportes|Fox Deportes|sports||FOX DEPORTES,FOX SPORTS DEPORTES
foxsoccer|Fox Soccer Plus|sports||FOX SOCCER PLUS,FOX SPORTS SOCCER
cbsgolazo|CBS Sports Golazo|sports||CBS SPORTS GOLAZO,CBS SPORTS GALAZO
bein|beIN Sports|sports||BEIN,BEIN SPORTS
eleven|Eleven Sports|sports||ELEVEN,ELEVEN SPORTS,ELEVEN SPORTS 1
tudn|TUDN|sports||TUDN,TUDN ZONA
goltv|GolTV|sports||GOL,GOLTV
tennis|Tennis Channel|sports||TENNIS
sstennis|SuperSport Tennis|sports||SUPERSPORT TENNIS
premierleague|Premier League TV|sports||PREMIER LEAGUE
willow|Willow|sports||WILLOW,WILLOW CRICKET
willowxtra|Willow Xtra|sports||WILLOW CRICKET EXTRA,WILLOW XTRA
f1channel|F1 Channel|sports||FORMULA 1,FORMULA 1 CHANNEL FM1TV
motoamerica|MotoAmerica TV|sports||MOTO AMERICA
mavtv|MAVTV|sports||MAV,MAVTV
redbull|Red Bull TV|sports||RED BULL
ufc|UFC Fight Pass|sports||UFC
wwe|WWE|sports||WWE
fightnet|Fight Network|sports||FIGHT
boxingtv|Boxing TV|sports||BOXING
billiard|Billiard TV|sports||BILLIARD
padel|Padeltime TV|sports||PADELTIME
pickleball|Pickleball TV|sports||PICKLEBALL,PICKLEBALL PBTV
mlbstrikezone|MLB Strike Zone|sports||MLB STRIKE ZONE
stadium|Stadium|sports||STADIUM
stadium1|Stadium 1|sports||STADIUM 1
stadium2|Stadium 2|sports||STADIUM 2
stadium3|Stadium 3|sports||STADIUM 3
fubosports|Fubo Sports|sports||FUBO SPORTS
fantasysports|Fantasy Sports Network|sports||FANTASY SPORTS
ginx|GINX Esports|sports||GINX ESPORTS
yahoosports|Yahoo Sports|sports||YAHOO SPORTS
afn|AFN Sports|sports||AFN SPORT,AFN SPORTS
cowboy|The Cowboy Channel|sports||COWBOY
outdoor|Outdoor Channel|sports||OUTDOOR
outside|Outside TV|sports||OUTSIDE
pursuit|Pursuit Channel|sports||PURSUIT
sportsman|Sportsman Channel|sports||SPORTSMAN
wfn|World Fishing Network|sports||WORLD FISHING
wired2fish|Wired2Fish|sports||WIRED2FISH
tvg|TVG|sports||TVG,TVG 2,TVG NETWORK 2
fanduel|FanDuel TV|sports||FANDUEL
"""

    class Net(val id: String, val label: String, val kind: String, val tags: Set<String>, val keys: List<String>, val rank: Int)

    private val NETS: Map<String, Net>
    private val BY_KEY: Map<String, Net>
    val ORDER: List<Net>

    init {
        val nets = LinkedHashMap<String, Net>()
        val byKey = HashMap<String, Net>()
        val order = ArrayList<Net>()
        for (line in CATALOG.trim().split("\n")) {
            val p = line.split("|")
            val n = Net(p[0], p[1], p[2], if (p[3].isEmpty()) emptySet() else p[3].split(",").toSet(), p[4].split(","), order.size)
            nets[n.id] = n
            order.add(n)
            for (k in n.keys) byKey[k] = n
        }
        NETS = nets
        BY_KEY = byKey
        ORDER = order
    }

    fun net(id: String): Net? = NETS[id]

    // ---------------------------------------------------------------- who a channel is

    private val BROADCAST = Regex("(?:^| )(ABC|CBS|NBC|FOX|CW|PBS)(?=\\d{0,2}(?: |$))")
    private val NUM_BROADCAST = Regex("^\\d{1,2}(ABC|CBS|NBC|FOX)$")
    private val NOT_AFFILIATE = Regex("\\b(?:NEWS|SPORTS?|BUSINESS|DEPORTES|WEATHER|KIDS|FAMILY|LIFE|SOUL|MOVIES|CLASSIC|PLUS|NOW|LIVE|REALITY|DRAMA|MYSTERY|COMEDY|SOCCER|NATION|EXTRA|COLLEGE|LATINO|ESPANOL|24 7)\\b")
    private val PHILLY = mapOf("WPVI" to "abc", "KYW" to "cbs", "WCAU" to "nbc", "WTXF" to "fox", "WPSG" to "cw", "WHYY" to "pbs")
    private val HOME = mapOf("WHNT" to "cbs", "WAAY" to "abc", "WAFF" to "nbc", "WZDX" to "fox", "WHDF" to "cw", "WHIQ" to "pbs")
    /** Free ad-supported services ("Pluto TV: Westerns"): a folder per service. Written out in full for RegexSafetyTest. */
    private val FAST_RE = Regex(
        "^\\s*[|\\[(]?\\s*(?:US\\s*[|:]\\s*)?(PLUTO TV|PLUTO|TUBI|SAMSUNG TV PLUS|XUMO|ROKU|PLEX|FREEVEE|VIZIO|LG CHANNELS|STIRR|DISTRO|FAST)\\s*[|:\\])]",
        RegexOption.IGNORE_CASE,
    )
    private val EVENT_RE = Regex(
        "^(NFL SUNDAY TICKET|NBA LEAGUE PASS|NHL CENTER ICE|MLB EXTRA INNINGS|ESPN EVENTS|ESPN\\+|NFL|NCAAF|NCAAB|NBA|NHL|MLB|WNBA|MLS|PEACOCK|PARAMOUNT\\+|DAZN|FLOSPORTS|B1G\\+|SEC\\+|ACCNX|VICTORY\\+|PPV|UFC)\\s*(?:EVENTS?\\s*)?#?\\s*(\\d{1,3})(?!\\d)\\s*(?:$|[:|\\-–])",
        RegexOption.IGNORE_CASE,
    )
    private val US_PREFIX = Regex("^\\s*[|\\[(]?\\s*(?:US|USA)\\s*[|:\\])]\\s*", RegexOption.IGNORE_CASE)
    private val GROUP_US = Regex("^\\s*US\\s*[|:]\\s*", RegexOption.IGNORE_CASE)
    private val H247 = Regex("(?:^|[^\\d])24\\s*[/x×-]?\\s*7(?!\\d)", RegexOption.IGNORE_CASE)
    private val SUFFIX_TAG = Regex(
        "\\s*(?:[(\\[]\\s*(?:HULU|PHILO|SLING|FUBO|DTV|DIRECTV|AT&T|ATT|YTTV|YOUTUBE TV|PLUTO|TUBI|XUMO|ROKU|PLEX|VIZIO|STIRR|US|USA|COPY|ALT)\\s*[)\\]]|\\s[|\\-–]\\s*(?:HULU|PHILO|SLING|FUBO|DTV|DIRECTV|AT&T|YTTV))\\s*$",
        RegexOption.IGNORE_CASE,
    )
    /** Lineup and league tags some playlists put before the name ("TV: A&E", "RK: 48 HOURS", "MLB: MLB NETWORK", "(GT) MSG"). */
    private val NAME_TAG = Regex("^\\s*(?:(?:TV|RK|ROKU|PRIME|NF|NETFLIX|AT&T|ATT|NBA|MLB|NHL|WNBA|HR|RX|4K|8K)\\s*:\\s*|\\(\\s*[A-Z]{2,3}\\s*\\)\\s+)", RegexOption.IGNORE_CASE)
    /** Small raised letters and digits providers put in names and groups ("ᴿᴬᵂ", "⁶⁰ᶠᵖˢ", "³⁸⁴⁰ᴾ"). */
    private val RAISED = Regex("[\\u0250-\\u02FF\\u1D00-\\u1DBF\\u2070-\\u209F\\u2C7C\\u2C7D\\uA7F8\\uA7F9\\u00B9\\u00B2\\u00B3]+")
    private val SPACE_RUN = Regex("\\s{2,}")
    /** A provider group, plain: "US| FLO ⱽᴵᴾ PPV" -> "FLO VIP PPV", "US| AT&T ᴿᴬᵂ ⁶⁰ᶠᵖˢ" -> "AT&T", "US| MILB PPV ⁽ᴮᴷ⁾" -> "MILB PPV". */
    private val GROUP_TAGS = Regex("(^|[^\\p{L}\\p{N}])(?:RAW|\\d{2,3}\\s?FPS|FHD|UHD|HD|SD|4K|8K|3840P|2160P|1080P|BK)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val GROUP_SYMBOLS = Regex("[\\p{So}\\p{Cf}\\uFE00-\\uFE0F]")
    private val GROUP_PREFIX = Regex("^\\s*[|\\[(]?\\s*(?:US|USA|8K|4K|RX|HR)\\s*[|:\\])]\\s*", RegexOption.IGNORE_CASE)
    private val GROUP_PUNCT = Regex("[()\\[\\]/|]+")
    private fun plainGroup(group: String): String {
        var g = group.replace("\u2C7D\u1D35\u1D3E", " VIP ")
        g = GROUP_PREFIX.replaceFirst(GROUP_SYMBOLS.replace(RAISED.replace(g, " "), " "), "")
        g = SPACE_RUN.replace(GROUP_PUNCT.replace(GROUP_TAGS.replace(g, "$1 "), " "), " ")
        return g.trim().uppercase(Locale.ROOT)
    }
    /** Event and pay-per-view groups ("FLO VIP PPV", "ESPN+ PPV", "MLB TEAM PPV", "NFL REPLAY"): a folder per provider. */
    private val EVENT_GROUP = Regex("(?:^| )(?:PPV|EVENTS?|REPLAY)(?: |$)")
    private val EVENT_GROUP_WORDS = Regex("(?:^| )(?:PPV|EVENTS?|VIP|ON AIR)(?= |$)")
    private val EVENT_GROUP_PPV = Regex("(?:^| )(?:PPV|EVENTS?)(?: |$)")
    private val EVENT_GROUP_NOT_PPV = Regex("(?:^| )(?:TEAM|BALLY SPORTS)$")
    /** Streaming lineups ("AT&T", "ROKU", "PRIME", "NETFLIX ON AIR", "DISNEY+ NETWORK"): a folder per service. */
    private val SERVICE_GROUP = Regex("^(AT&T|ATT|ROKU|TV|PRIME|NETFLIX|PEACOCK|DISNEY\\+|PARAMOUNT\\+|HBO MAX|HULU|SPECTRUM)(?: (?:NETWORK|ON AIR|ORIGINALS?|CITY))?$")
    private val SERVICE_LABEL = mapOf(
        "AT&T" to "AT&T", "ATT" to "AT&T", "ROKU" to "Roku", "TV" to "US TV", "PRIME" to "Prime Video", "NETFLIX" to "Netflix", "PEACOCK" to "Peacock",
        "DISNEY+" to "Disney+", "PARAMOUNT+" to "Paramount+", "HBO MAX" to "HBO Max", "HULU" to "Hulu", "SPECTRUM" to "Spectrum",
    )
    private val TV_SHOWS_GROUP = Regex("(?:^| )TV SHOWS(?: |$)")
    private val RELAX_GROUP = Regex("(?:^| )RELAX(?: |$)")
    private val RACING_GROUP = Regex("(?:^| )HORSE RACING(?: |$)")
    private val GROUP_4K = Regex("^\\s*[|\\[(]?\\s*4K\\s*[|:\\])]", RegexOption.IGNORE_CASE)
    /** Regional sports networks, old and new names ("FOX SPORTS DETROIT", "CSN BOSTON", "MSG ZONE1", "PAC 12 OREGON"): one folder. */
    private val RSN = Regex("^(?:FOX SPORTS (?!1(?: |$)|2(?: |$)|ONE|RACING|SOCCER|DEPORTES|NEWS)|NBC SPORTS (?!NETWORK|NOW|NEWS)|CSN |AT&T SPORTSNET|ROOT SPORTS|FANDUEL (?:SPORTS|MIDWEST)|BALLY SPORTS|SPECTRUM SPORTSNET|SPORTSNET (?:LA|NEW YORK|PITTSBURGH)|SNY(?: |$)|MSG|NESN|YES(?: |$)|MARQUEE|MONUMENTAL|ALTITUDE|MASN|MASSN|SPACE CITY|CHICAGO SPORTS|PAC 12 )")
    /** Premium movie channels beyond the main one ("HBO ZONE", "STARZ ENCORE WESTERNS", "MOREMAX"): a folder per brand. */
    private val PREMIUM = Regex("^(STARZ ENCORE|ENCORE|HBO|CINEMAX|MOREMAX|OUTERMAX|MOVIEMAX|THRILLERMAX|ACTION MAX|5 STARMAX|SHOWTIME|STARZ|EPIX|MGM|SCREENPIX|MOVIEPLEX|INDIEPLEX|RETROPLEX)(?: |$)")
    private val PREMIUM_LABEL = mapOf(
        "STARZ ENCORE" to "Starz Encore", "ENCORE" to "Starz Encore", "HBO" to "HBO", "CINEMAX" to "Cinemax", "MOREMAX" to "Cinemax", "OUTERMAX" to "Cinemax",
        "MOVIEMAX" to "Cinemax", "THRILLERMAX" to "Cinemax", "ACTION MAX" to "Cinemax", "5 STARMAX" to "Cinemax", "SHOWTIME" to "Showtime", "STARZ" to "Starz",
        "EPIX" to "MGM+", "MGM" to "MGM+", "SCREENPIX" to "ScreenPix", "MOVIEPLEX" to "MoviePlex", "INDIEPLEX" to "MoviePlex", "RETROPLEX" to "MoviePlex",
    )
    /** Channel lineups of one brand: a folder each. */
    private val BRAND = Regex("^(MUSIC CHOICE|DAZN)(?: |$)")
    private val BRAND_KIND = mapOf("MUSIC CHOICE" to "music", "DAZN" to "sports")
    private val BRAND_LABEL = mapOf("MUSIC CHOICE" to "Music Choice", "DAZN" to "DAZN")
    /** Stations of a network named by their number ("TELEMUNDO 51 (WSCV) MIAMI", "UNIVISION23 (WLTV)", "MY 9 WWOR NEW YORK"). */
    private val STATION_NET = mapOf("TELEMUNDO" to "telemundo", "UNIVISION" to "univision", "MY" to "mynet", "MYTV" to "mynet")
    private val DIGITS_END = Regex("\\d+$")
    private val ALL_DIGITS = Regex("^\\d+$")
    /**
     * Other local stations and local news: a call sign ("(WHDH)", "WXYZ-TV"), a known station name ("KIRO 7", "WGN9"), a station number
     * with "PLUS" ("FOX 5 PLUS"), or a city news name ("NBC BOSTON NEWS").
     */
    private val STATION = Regex("\\([KW][A-Z]{2,3}(?:-[A-Z]{1,2}\\d?)?\\)|\\b[KW][A-Z]{2,3}-(?:TV|DT|CD|LD)\\d?\\b|^(?:NEWS 12|NEW JERSEY 12|SPECTRUM (?:BAY )?NEWS|BOSTON 25|7NEWS|KTLA|KUHT|KIRO|WGN ?\\d|CBSN [A-Z]|DISTRICT (?:KNOWLEDGE|OF COLUMBIA))|^(?:ABC|CBS|NBC|FOX) (?:\\d{1,2} PLUS|[A-Z ]+ NEWS)(?: |$)")
    private val PPV_NAME = Regex("^\\s*PPV\\s*[:|]", RegexOption.IGNORE_CASE)
    private val H24H = Regex("(?:^|[^\\d])24\\s?H(?:RS?)?(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    /** Feed marks: "(2)", "(E)", "(West)", "#2", and the one or two letter feed tags some providers add ("(A)", "(PC)", "(SP)"). */
    private val FEED_MARKS = Regex("[(\\[]\\s*(?:\\d{1,2}|[A-Z]{1,2}|EAST|WEST|PACIFIC)\\s*[)\\]]|#\\d{1,2}\\b", RegexOption.IGNORE_CASE)
    private val QUALITY = Regex(
        "(^|[^\\p{L}\\p{N}])(?:RAW|\\d{2,3}\\s?FPS|FHD|UHD|HD|SD|HQ|4K|8K|3840P|2160P|1080P|1080I|720P|480P|HEVC|H\\.?26[45]|HDR10\\+?|HDR|BACKUP|VIP|LOW|MULTI-?AUDIO)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    /** "CENTRAL" is not a feed word: it ends real names ("Comedy Central", "Game Show Central"). */
    private val FEED_WORDS = setOf("EAST", "WEST", "PACIFIC", "MOUNTAIN", "ALT", "ALTERNATE", "FEED", "BACKUP", "OPT", "OPTION")
    private val SUFFIX = setOf("NETWORK", "CHANNEL", "TV")
    private val SPLIT = Regex("[^\\p{L}\\p{N}&+!]+")
    private val SHIFT_WORD = Regex("^\\+\\d$")
    private val SHIFT_END = Regex("\\+\\d$")
    private val SIGN_TAIL = Regex("(?:TV|DT|CD|LD)?\\d*$")
    private val PHILADELPHIA = Regex("\\bPHILADELPHIA\\b")

    private fun words(s: String): MutableList<String> = s.uppercase(Locale.ROOT).split(SPLIT).filter { it.isNotEmpty() }.toMutableList()

    /** Network key of a clean name: "AMC West", "AMC (2)", "AMC Channel", "AMC +1" -> "AMC"; "FX" and "FXX" stay apart. */
    fun netKey(clean: String): String {
        var t = clean
        repeat(2) { t = SUFFIX_TAG.replace(t, "") }
        t = FEED_MARKS.replace(t, " ")
        t = QUALITY.replace(t, "$1 ")
        val w = words(t)
        if (w.size > 1 && SHIFT_WORD.matches(w.last())) w.removeAt(w.size - 1)
        if (w.isNotEmpty() && w.last().length > 2 && SHIFT_END.containsMatchIn(w.last())) w[w.size - 1] = w.last().dropLast(2)
        w.removeAll { it in FEED_WORDS }
        if (w.size > 1 && (w.last() == "E" || w.last() == "W")) w.removeAt(w.size - 1)
        while (w.size > 1 && w.last() in SUFFIX) w.removeAt(w.size - 1)
        return w.joinToString(" ").ifEmpty { clean.uppercase(Locale.ROOT).trim() }
    }

    private val Q_4K = Regex("(?<![\\p{L}\\p{N}])(?:4K|UHD|2160P?)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val Q_FHD = Regex("(?<![\\p{L}\\p{N}])(?:FHD|1080[PI]?)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val Q_HD = Regex("(?<![\\p{L}\\p{N}])(?:HD|720P?)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val Q_SD = Regex("(?<![\\p{L}\\p{N}])(?:SD|480P?|360P?|LOW)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val Q_FPS = Regex("(?:50|60)\\s?FPS", RegexOption.IGNORE_CASE)
    private val Q_RAW = Regex("(?<![\\p{L}\\p{N}])RAW(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val Q_BACKUP = Regex("(?<![\\p{L}\\p{N}])(?:BACKUP|ALT|ALTERNATE|OPT|OPTION|COPY)(?![\\p{L}\\p{N}])|[(\\[]\\s*\\d{1,2}\\s*[)\\]]", RegexOption.IGNORE_CASE)
    private val Q_WEST = Regex("(?<![\\p{L}\\p{N}])(?:WEST|PACIFIC|MOUNTAIN)(?![\\p{L}\\p{N}])|[(\\[]\\s*W\\s*[)\\]]|\\sW$", RegexOption.IGNORE_CASE)
    private val Q_SHIFT = Regex("\\+\\s?\\d(?!\\p{N})")

    /** Feed quality from its own name: 4K, FHD, HD, SD; 60 fps and RAW up; backups, West and +1 down; a US feed up. */
    fun feedScore(raw: String, group: String, us: Boolean): Int {
        val t = "$raw $group"
        var s = when {
            Q_4K.containsMatchIn(t) -> 40
            Q_FHD.containsMatchIn(t) -> 30
            Q_HD.containsMatchIn(t) -> 20
            Q_SD.containsMatchIn(t) -> -10
            else -> 0
        }
        if (Q_FPS.containsMatchIn(t)) s += 6
        if (Q_RAW.containsMatchIn(t)) s += 4
        if (Q_BACKUP.containsMatchIn(raw)) s -= 30
        if (Q_WEST.containsMatchIn(raw)) s -= 8
        if (Q_SHIFT.containsMatchIn(raw)) s -= 15
        if (us) s += 10
        return s
    }

    private fun title(s: String): String = s.split(" ").joinToString(" ") { x -> if (x.length > 3) x[0] + x.substring(1).lowercase(Locale.ROOT) else x }

    private fun fastOf(m: MatchResult): LiveIdent {
        val p = m.groupValues[1].uppercase(Locale.ROOT).let { if (it == "PLUTO") "PLUTO TV" else it }
        return LiveIdent("fast:$p", title(p) + " (free channels)", "free", null, "", true)
    }

    private fun affiliate(id: String, region: String): LiveIdent {
        val n = NETS.getValue(id)
        return LiveIdent(id, n.label, n.kind, n, region, false)
    }

    /** Who a channel is. [section] is the old organizer's section, the fallback kind for a network not in the catalog. */
    fun identify(raw: String, group: String, country: String, section: String): LiveIdent {
        val us = country == "US" || country == "" || country == LiveOrganizer.UNKNOWN_COUNTRY
        if (!us) return LiveIdent("intl:$country", "$country channels", "intl", null, "", true)
        // A second country tag after "US:" ("US: IT| PIRATI DEI CARAIBI CHANNEL") is the channel's real country.
        val inner = US_PREFIX.replaceFirst(raw, "")
        if (inner != raw) {
            val c2 = LiveOrganizer.country("", inner)
            if (c2 != "US" && c2 != LiveOrganizer.UNKNOWN_COUNTRY) return LiveIdent("intl:$c2", "$c2 channels", "intl", null, "", true)
        }
        var clean = SPACE_RUN.replace(RAISED.replace(LiveOrganizer.cleanName(raw), " "), " ").trim()
        repeat(2) { clean = NAME_TAG.replaceFirst(clean, "") }
        val ev = EVENT_RE.find(clean) ?: EVENT_RE.find(inner)
        if (ev != null) {
            val p = ev.groupValues[1].uppercase(Locale.ROOT)
            return LiveIdent("ev:$p", "$p event channels", "events", null, "", true)
        }
        if (PPV_NAME.containsMatchIn(inner)) return LiveIdent("ev:PPV", "PPV event channels", "events", null, "", true)
        if (H247.containsMatchIn("$raw $group") || H24H.containsMatchIn("$raw $group")) return LiveIdent("f:247", "24/7 channels", "247", null, "", true)
        // A free-service channel named by its service ("Pluto TV: Big Brother") stays with that service, even when a network shares the name.
        FAST_RE.find(raw)?.let { return fastOf(it) }
        val k = netKey(clean)
        BY_KEY[k]?.let { return LiveIdent(it.id, it.label, it.kind, it, "national", false) }
        // Local stations: "FOX 54 WZDX Huntsville", "6ABC WPVI Philadelphia", "CBS 2 New York" belong to their network.
        val w = words(clean)
        for (x in w) {
            val sign = SIGN_TAIL.replaceFirst(x, "")
            HOME[sign]?.let { return affiliate(it, "home") }
            PHILLY[sign]?.let { return affiliate(it, "philly") }
        }
        val kk = w.joinToString(" ")
        val b = BROADCAST.find(kk)?.groupValues?.get(1) ?: NUM_BROADCAST.find(w.firstOrNull() ?: "")?.groupValues?.get(1)
        if (b != null && !NOT_AFFILIATE.containsMatchIn(kk)) {
            return affiliate(b.lowercase(Locale.ROOT), if (PHILADELPHIA.containsMatchIn(kk)) "philly" else if (w.size > 1) "market" else "national")
        }
        val w0 = w.firstOrNull() ?: ""
        val sn = DIGITS_END.replaceFirst(w0, "")
        STATION_NET[sn]?.let { id ->
            if (sn == "TELEMUNDO" || sn == "UNIVISION" || w0.lastOrNull()?.isDigit() == true || ALL_DIGITS.matches(w.getOrNull(1) ?: "")) {
                return affiliate(id, if (w.size > 1) "market" else "national")
            }
        }
        if (RSN.containsMatchIn(kk)) return LiveIdent("f:rsn", "Regional sports networks", "sports", null, "", true)
        PREMIUM.find(k)?.let { val l = PREMIUM_LABEL.getValue(it.groupValues[1]); return LiveIdent("pm:$l", "$l channels", "movies", null, "", true) }
        BRAND.find(k)?.let { val b2 = it.groupValues[1]; return LiveIdent("br:$b2", BRAND_LABEL.getValue(b2) + " channels", BRAND_KIND.getValue(b2), null, "", true) }
        if (STATION.containsMatchIn(clean.uppercase(Locale.ROOT))) return LiveIdent("f:stations", "Local stations", "local", null, "", true)
        val fast = FAST_RE.find(raw) ?: FAST_RE.find(GROUP_US.replaceFirst(group, "") + ":")
        if (fast != null) return fastOf(fast)
        val kind = when (section) {
            LiveOrganizer.SPORTS -> "sports"
            LiveOrganizer.NEWS -> "news"
            LiveOrganizer.ENTERTAINMENT -> "ent"
            LiveOrganizer.MOVIES -> "movies"
            LiveOrganizer.KIDS -> "kids"
            LiveOrganizer.LOCAL -> "local"
            LiveOrganizer.MUSIC -> "music"
            LiveOrganizer.H24 -> "247"
            LiveOrganizer.EVENTS -> "events"
            else -> "other"
        }
        val pg = plainGroup(group)
        if (EVENT_GROUP.containsMatchIn(pg)) {
            val p = SPACE_RUN.replace(EVENT_GROUP_WORDS.replace(pg, " "), " ").trim().ifEmpty { "PPV" }
            val ppv = EVENT_GROUP_PPV.containsMatchIn(pg) && !EVENT_GROUP_NOT_PPV.containsMatchIn(p)
            return LiveIdent("ev:$p", p + if (ppv) " event channels" else " channels", "events", null, "", true)
        }
        // The group names a folder for channels with no network of their own ([groups] folds a name in only when every feed
        // of it has one, so "AT&T: Crime + Investigation" stays with the other Crime + Investigation feeds).
        val svc = SERVICE_GROUP.find(pg)
        val fold = when {
            svc != null -> SERVICE_LABEL.getValue(svc.groupValues[1]).let { LiveFold("svc:$it", "$it channels", kind) }
            TV_SHOWS_GROUP.containsMatchIn(pg) -> LiveFold("f:247", "24/7 channels", "247")
            RELAX_GROUP.containsMatchIn(pg) -> LiveFold("f:relax", "Relax channels", "music")
            RACING_GROUP.containsMatchIn(pg) -> LiveFold("f:racing", "Horse racing channels", "sports")
            // A provider's 4K showcase group ("4K| ³⁸⁴⁰ᴾ"): channels from many countries.
            pg.isEmpty() && GROUP_4K.containsMatchIn(group) -> LiveFold("f:4k", "4K channels", kind)
            else -> null
        }
        return LiveIdent(k, "", kind, null, "", false, fold)
    }

    private val CHUNKS = Regex("\\d+|\\D+")

    /** A to Z with numbers in number order ("NFL 2" before "NFL 10"); the same on the web, so both list alike. */
    fun cmpLabel(a: String, b: String): Int {
        val x = CHUNKS.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val y = CHUNKS.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            if (x[i] == y[i]) continue
            val dx = x[i][0].isDigit() && x[i][0] in '0'..'9'
            val dy = y[i][0].isDigit() && y[i][0] in '0'..'9'
            if (dx && dy) {
                val d = x[i].toBigInteger().compareTo(y[i].toBigInteger())
                if (d != 0) return d
                return x[i].length - y[i].length
            }
            return if (x[i] < y[i]) -1 else 1
        }
        return x.size - y.size
    }

    private fun regionRank(r: String): Int = when (r) { "home" -> 0; "national", "" -> 1; "philly" -> 2; "market" -> 3; else -> 1 }
    private val SERIES = Regex("^(.*\\D) ?\\d{1,3}$")

    /** Groups a playlist by network. Every channel is in exactly one group. [country] and [section] per channel. */
    fun groups(chs: List<Channel>, country: (Int) -> String, section: (Int) -> String): LiveGroups {
        val by = LinkedHashMap<String, LiveGroup>()
        val ident = arrayOfNulls<LiveIdent>(chs.size)
        val us = BooleanArray(chs.size)
        for ((i, c) in chs.withIndex()) {
            val ctry = country(i)
            val id = identify(c.name, c.group, ctry, section(i))
            ident[i] = id
            us[i] = ctry == "US"
            by.getOrPut(id.key) { LiveGroup(id.key, id.label, id.kind, id.net, id.family, ArrayList()) }.feeds.add(i)
        }
        // Names with no network whose every feed sits in a folder group ("US| AT&T", "HR| HORSE RACING"): into that folder.
        for (g in by.values.toList()) {
            if (g.family || g.net != null || !g.feeds.all { ident[it]!!.fold != null }) continue
            val f = ident[g.feeds[0]]!!.fold!!
            by.getOrPut(f.key) { LiveGroup(f.key, f.label, f.kind, null, true, ArrayList()) }.feeds.addAll(g.feeds)
            by.remove(g.id)
        }
        // Numbered series of one name ("Music Choice 01" to "60"): one folder.
        val series = LinkedHashMap<String, MutableList<LiveGroup>>()
        for (g in by.values) {
            if (g.family || g.net != null) continue
            val m = SERIES.matchEntire(g.id) ?: continue
            val p = m.groupValues[1].trim()
            if (p.length > 1) series.getOrPut(p) { ArrayList() }.add(g)
        }
        for ((p, gs) in series) {
            if (gs.size < 4) continue
            val f = LiveGroup("num:$p", "", gs[0].kind, null, true, gs.flatMapTo(ArrayList()) { it.feeds })
            for (g in gs) by.remove(g.id)
            by[f.id] = f
        }
        val ids = Array(chs.size) { ident[it]!! }
        val score = IntArray(chs.size)
        val out = by.values.toMutableList()
        for (g in out) {
            for (i in g.feeds) score[i] = feedScore(chs[i].name, chs[i].group, us[i])
            if (g.family) {
                g.feeds.sortWith { a, b -> cmpLabel(LiveOrganizer.cleanName(chs[a].name), LiveOrganizer.cleanName(chs[b].name)).let { if (it != 0) it else a - b } }
            } else {
                g.feeds.sortWith(compareBy<Int> { regionRank(ids[it].region) }.thenByDescending { score[it] }.thenBy { it })
            }
            if (g.label.isEmpty()) {
                g.label = if (g.id.startsWith("num:")) title(g.id.substring(4)) + " channels"
                else SUFFIX_TAG.replaceFirst(LiveOrganizer.cleanName(chs[g.feeds[0]].name), "").trim()
            }
        }
        out.sortWith { a, b -> cmpLabel(a.label, b.label).let { if (it != 0) it else cmpLabel(a.id, b.id) } }
        return LiveGroups(out, ids, score)
    }

    // ---------------------------------------------------------------- what a programme is

    private val LEAGUE_TITLE = listOf(
        "NFL" to Regex("^(?:NFL(?: Football)?|(?:Sunday|Monday|Thursday) Night Football)\\b", RegexOption.IGNORE_CASE),
        "NCAAF" to Regex("^(?:College Football|NCAA Football|NCAAF|CFB)\\b", RegexOption.IGNORE_CASE),
        "MLB" to Regex("^(?:MLB(?: Baseball)?|World Series|ALCS|NLCS|ALDS|NLDS|MLB Postseason|MLB Wild Card)\\b", RegexOption.IGNORE_CASE),
        "NHL" to Regex("^(?:NHL(?: Hockey)?|Stanley Cup)\\b", RegexOption.IGNORE_CASE),
        "NBA" to Regex("^(?:NBA(?: Basketball)?|NBA Finals|NBA Playoffs|NBA Cup)\\b", RegexOption.IGNORE_CASE),
    )
    private val EXCLUDED = Regex("\\b(?:WNBA|MLS|soccer|premier league|futbol|f[uú]tbol|liga|serie a|bundesliga|uefa|champions league|nwsl|women'?s)\\b", RegexOption.IGNORE_CASE)
    private val NOT_GAME = Regex("\\b(?:replay|re-?air|encore|rebroadcast|classic|classics|highlights|condensed|in 60|pre-?game|post-?game|preview|recap|countdown|kickoff show|tonight|live from|draft|combine|press conference)\\b", RegexOption.IGNORE_CASE)
    private val REPLAY_DESC = Regex("\\b(?:replay|re-?air|rebroadcast|encore presentation)\\b", RegexOption.IGNORE_CASE)
    private val MATCHUP = Regex("^(.+?)\\s+(?:at|vs\\.?|v\\.?|versus|@)\\s+(.+?)$", RegexOption.IGNORE_CASE)
    private val LEAD_COLON = Regex("^[^:]*:\\s*")
    private val SEED_AWAY = Regex("^\\(\\d+\\)\\s*|\\s*\\(\\d+\\)$")
    private val PAREN_TAIL = Regex("\\s*\\(.*\\)\\s*$")
    private val SEED_LEAD = Regex("^\\(\\d+\\)\\s*")
    private val THREE_LETTERS = Regex("\\p{L}{3}")
    private val NFL_CAT = Regex("\\bnfl\\b")
    private val SPORTS_EVENT = Regex("sports event", RegexOption.IGNORE_CASE)

    /** A live-game listing in one of J's five leagues, as (league, away, home); home "" when no teams are named. */
    fun gameOf(p: Programme?, leagueHint: String = ""): Triple<String, String, String>? {
        if (p == null || p.title.isEmpty()) return null
        val t = p.title.trim()
        if (EXCLUDED.containsMatchIn(t + " " + p.categories.joinToString(" "))) return null
        if (p.rerun || NOT_GAME.containsMatchIn(t) || NOT_GAME.containsMatchIn(p.sub) || REPLAY_DESC.containsMatchIn(p.desc)) return null
        var league = LEAGUE_TITLE.firstOrNull { it.second.containsMatchIn(t) }?.first ?: ""
        val cats = p.categories.joinToString(" ").lowercase(Locale.ROOT)
        if (league.isEmpty() && leagueHint.isNotEmpty()) league = leagueHint
        if (league.isEmpty() && NFL_CAT.containsMatchIn(cats)) league = "NFL"
        if (league.isEmpty() && cats.contains("college football")) league = "NCAAF"
        if (league.isEmpty()) return null
        val rest = LEAD_COLON.replaceFirst(t, "")
        val m = MATCHUP.find(rest) ?: MATCHUP.find(p.sub)
        if (m == null) {
            // "MLB Baseball: World Series Game 3": a game with no teams named, when the guide marks it live or a sports event.
            return if (rest != t && (p.live || SPORTS_EVENT.containsMatchIn(cats)) && THREE_LETTERS.containsMatchIn(rest)) Triple(league, rest, "") else null
        }
        val away = SEED_AWAY.replace(m.groupValues[1], "").trim()
        val home = SEED_LEAD.replaceFirst(PAREN_TAIL.replaceFirst(m.groupValues[2], ""), "").trim()
        if (!THREE_LETTERS.containsMatchIn(away) || !THREE_LETTERS.containsMatchIn(home)) return null
        return Triple(league, away, home)
    }

    private val NICK_CLEAN = Regex("[^a-z0-9 ]+")
    private val SPACES = Regex("\\s+")
    private fun nick(team: String, league: String): String {
        val w = NICK_CLEAN.replace(team.lowercase(Locale.ROOT), " ").trim().split(SPACES)
        return if (league == "NCAAF") w.joinToString(" ") else w.lastOrNull() ?: ""
    }

    fun gameKey(league: String, a: String, b: String): String =
        league + ":" + if (b.isNotEmpty()) listOf(nick(a, league), nick(b, league)).sorted().joinToString("|") else a.lowercase(Locale.ROOT)

    private val I = RegexOption.IGNORE_CASE
    private val MIL_EXCL = Regex("\\b(?:star wars|storage wars|cyber ?wars?|bidding wars?|shark wars|console wars|cupcake wars|bridal|food wars|wedding wars|wars of the roses|war of the worlds|warriors? (?:of|basketball)|golden state)\\b", I)
    private val MILITARY = Regex("\\b(?:military|army|navy|naval|air force|usaf|marines?|marine corps|coast guard|pentagon|special forces|navy seals?|green berets|combat|battle(?:s|field|ship)?|warship|warplanes?|submarines?|aircraft carrier|bomber|fighter (?:jet|pilot)s?|dogfights?|top guns?|weapons school|d-day|normandy|wwii|ww2|world war|civil war|vietnam war|korean war|gulf war|veterans?|medal of honor|tanks?|artillery|infantry|airborne|paratroopers?|warfare|invasion|blitz|luftwaffe|pearl harbor|iwo jima|midway|hitler|nazi|wars?)\\b", I)
    private val AIR_FORCE = Regex("\\b(?:air force|usaf|airmen|fighter (?:jet|pilot)s?|top guns?|weapons school|f-1[56]|f-22|f-35|f-117|b-52|b-17|b-2|b-29|a-10|strike eagle|warthog|thunderbirds|stealth|dogfights?|air war|aviation|aerial combat|air warriors|sabres?)\\b", I)
    private val HISTORY = Regex("\\b(?:history|historic(?:al)?|documentary|ancient|archaeolog\\w*|civil war|wwii|ww2|world war|revolution(?:ary)?|founding fathers|presidents?|empire|medieval|pharaohs?|ken burns|american experience|nova|frontline|smithsonian|biography|d-day|declassified|lost (?:city|tombs?)|egypt|romans?|vikings?)\\b", I)
    private val TECH = Regex("\\b(?:ai|a\\.i\\.|artificial intelligence|machine learning|cyber\\w*|hack(?:er|ers|ing|ed)?|robot\\w*|computers?|computing|silicon valley|tech|technology|internet|software|chips?|semiconductors?|quantum|algorithms?|data breach|ransomware|encryption|digital|gadgets?|innovation|space ?x|rockets?)\\b", I)
    private val DOC_CATS = Regex("\\b(?:documentary|history|historical|biography|docuseries|science|nature|military|war)\\b", I)
    private val NOT_DOC_CATS = Regex("\\b(?:reality|sitcom|comedy|game show|talk|news|cooking|home improvement|sports|soap|animation|movie)\\b", I)
    private val SCIFI = Regex("science[ -]fiction|sci-?fi", I)
    private val MIL_CAT = Regex("\\b(?:military|war)\\b", I)
    private val NOT_TECH_CAT = Regex("\\b(?:sitcom|reality|game show|cooking)\\b", I)
    private val TECH_CAT = Regex("\\b(?:technology|tech)\\b", I)
    private val AI_WORD = Regex("\\b(?:AI|A\\.I\\.)\\b")
    private val AI_ANY = Regex("\\bAI\\b")
    private val TECH_OK_CAT = Regex("\\b(?:science|documentary|business)\\b", I)
    private val REALITY = Regex("\\breality\\b", I)

    /** Topics of a programme from its categories, title and description. */
    class Topics(val docs: Boolean, val military: Boolean, val airforce: Boolean, val tech: Boolean)

    fun topics(p: Programme?): Topics {
        if (p == null) return Topics(false, false, false, false)
        val cats = SCIFI.replace(p.categories.joinToString(" "), "scifi")
        val text = p.title + " " + p.sub + " " + p.desc
        val all = "$text $cats"
        val docCat = DOC_CATS.containsMatchIn(cats)
        val notDoc = NOT_DOC_CATS.containsMatchIn(cats) && !docCat
        val mil = !notDoc && (MIL_CAT.containsMatchIn(cats) ||
            (MILITARY.containsMatchIn(all) && !MIL_EXCL.containsMatchIn(all) && (docCat || cats.isEmpty() || MILITARY.containsMatchIn(p.title))))
        val tech = !NOT_TECH_CAT.containsMatchIn(cats) && (TECH_CAT.containsMatchIn(cats) ||
            (TECH.containsMatchIn(AI_ANY.replace(text, " ai ")) && (AI_WORD.containsMatchIn(text) || TECH.containsMatchIn(p.title) || TECH_OK_CAT.containsMatchIn(cats))))
        val docs = !notDoc && (docCat || (cats.isEmpty() && HISTORY.containsMatchIn(text)) || (HISTORY.containsMatchIn(p.title) && !REALITY.containsMatchIn(cats)))
        return Topics(docs, mil, mil && AIR_FORCE.containsMatchIn(all), tech)
    }

    // ---------------------------------------------------------------- rows

    private val ZONE: TimeZone = TimeZone.getTimeZone("America/Chicago")
    private fun fmt(ms: Long, pattern: String): String = SimpleDateFormat(pattern, Locale.US).apply { timeZone = ZONE }.format(Date(ms))

    /** "4:00 PM CT". */
    fun timeCT(ms: Long): String = fmt(ms, "h:mm a") + " CT"
    private fun dayKey(ms: Long): String = fmt(ms, "yyyy-MM-dd")
    private fun hourCT(ms: Long): Int = fmt(ms, "H").toInt()

    /** "Today 6:00 PM CT", "Tomorrow 1:00 PM CT", "Sun 12:00 PM CT". */
    fun whenCT(ms: Long, now: Long): String {
        val d = dayKey(ms)
        val label = if (d == dayKey(now)) "Today" else if (d == dayKey(now + 86_400_000L)) "Tomorrow" else fmt(ms, "EEE")
        return label + " " + timeCT(ms)
    }

    private val LEAGUE_ORDER = mapOf("NFL" to 0, "NCAAF" to 1, "NBA" to 2, "MLB" to 3, "NHL" to 4)
    private val LEAGUE_LABEL = mapOf("NFL" to "NFL", "NCAAF" to "College Football", "NBA" to "NBA", "MLB" to "MLB", "NHL" to "NHL")
    private val GAME_MINUTES = mapOf("NFL" to 210, "NCAAF" to 210, "MLB" to 180, "NBA" to 150, "NHL" to 150)
    private val LEAGUE_HINT = mapOf("nfln" to "NFL", "redzone" to "NFL", "mlbn" to "MLB", "nhln" to "NHL", "nbatv" to "NBA")
    private val EVENT_LEAGUE = mapOf(
        "EV:NFL" to "NFL", "EV:NFL SUNDAY TICKET" to "NFL", "EV:NCAAF" to "NCAAF", "EV:NBA" to "NBA", "EV:NBA LEAGUE PASS" to "NBA",
        "EV:NHL" to "NHL", "EV:NHL CENTER ICE" to "NHL", "EV:MLB" to "MLB", "EV:MLB EXTRA INNINGS" to "MLB",
    )
    private val TEAM_ALIAS = mapOf("76ERS" to listOf("SIXERS"))
    private val PHILLY_TEAM = Regex("philadelphia|eagles|phillies|flyers|76ers|sixers", I)
    private val LABEL_TAIL = Regex("\\s*[:|].*$")

    /** A favorite team plays in this game. */
    fun isMine(team: HomeTeam, league: String, a: String, b: String): Boolean {
        if (team.league != league) return false
        val n = team.name.uppercase(Locale.ROOT)
        val names = listOf(n) + TEAM_ALIAS[n].orEmpty()
        return listOf(a, b).any { t ->
            val w = " " + words(t).joinToString(" ") + " "
            names.any { x -> w.contains(" " + words(x).joinToString(" ") + " ") }
        }
    }

    private class Feed(val i: Int, val live: Boolean, val net: LiveGroup, val event: Boolean = false, val cast: Boolean = false)

    private class Cand(val key: String, val league: String, val away: String, val home: String) {
        var start = 0L
        var end = 0L
        var live = false
        var detail = ""
        var title = ""
        val feeds = ArrayList<Feed>()
        var epg = 0
        var espn: HomeGame? = null
        var feedList: List<Feed> = emptyList()
        var netLabel = ""
    }

    /**
     * The default view: My Teams, Live Games, History & Docs On Now, Military On Now, Cyber, AI & Tech On Now and
     * Primetime Entertainment. [progs] gives a channel's programmes (sorted, may be empty); [eventOf] an event
     * channel's own listing ([LiveFill.eventProgramme]).
     */
    fun home(
        idx: LiveGroups,
        chs: List<Channel>,
        progs: (Int) -> List<Programme>,
        games: List<HomeGame>,
        teams: List<HomeTeam>,
        now: Long,
        eventOf: (Int) -> Programme?,
    ): List<HomeRow> {
        val g0 = idx.list
        val byKey = LinkedHashMap<String, Cand>()
        fun want(league: String, a: String, b: String): Cand = byKey.getOrPut(gameKey(league, a, b)) { Cand(gameKey(league, a, b), league, a, b) }
        fun domestic(g: LiveGroup) = !g.id.startsWith("intl:") && !g.id.startsWith("fast:") && g.id != "f:247"

        // ---- games: from the guide, event channels and ESPN, merged by teams
        for (g in g0) {
            if (!domestic(g)) continue
            val hint = g.net?.let { LEAGUE_HINT[it.id] } ?: ""
            for (i in g.feeds) {
                val list = progs(i)
                if (list.isNotEmpty()) {
                    for (p in list) {
                        if (p.end <= now || p.start > now + 8 * 3_600_000L) continue
                        val gm = gameOf(p, hint) ?: continue
                        val x = want(gm.first, gm.second, gm.third)
                        val live = p.start <= now && p.end > now
                        if (live) { x.live = true; x.epg++ }
                        if (x.start == 0L || p.start < x.start) { x.start = p.start; x.end = p.end }
                        if (x.title.isEmpty()) x.title = if (gm.third.isNotEmpty()) gm.second + " at " + gm.third else gm.second
                        x.feeds.add(Feed(i, live, g))
                    }
                } else if (g.id.startsWith("ev:")) {
                    val ep = eventOf(i) ?: continue
                    val lg = EVENT_LEAGUE[g.id.uppercase(Locale.ROOT)] ?: continue
                    val m = MATCHUP.find(ep.title) ?: continue
                    val x = want(lg, m.groupValues[1], m.groupValues[2])
                    val live = ep.untimed || (ep.start <= now && ep.end > now)
                    if (x.start == 0L || ep.start < x.start) { x.start = ep.start; x.end = ep.end }
                    if (live) x.live = true
                    if (x.title.isEmpty()) x.title = m.groupValues[1] + " at " + m.groupValues[2]
                    x.feeds.add(Feed(i, live, g, event = true))
                }
            }
        }
        for (e in games) {
            if (e.league !in LEAGUE_ORDER || e.state == "post") continue
            val a = if (e.league == "NCAAF") e.away.short.ifEmpty { e.away.name } else e.away.name.ifEmpty { e.away.nick }
            val b = if (e.league == "NCAAF") e.home.short.ifEmpty { e.home.name } else e.home.name.ifEmpty { e.home.nick }
            val x = want(e.league, a, b)
            x.espn = e
            x.detail = e.detail
            if (e.state == "in") x.live = true
            if (e.start != 0L) x.start = e.start
            if (x.end == 0L) x.end = e.start + (GAME_MINUTES[e.league] ?: 180) * 60_000L
            x.title = e.away.short.ifEmpty { e.away.nick.ifEmpty { a } } + " at " + e.home.short.ifEmpty { e.home.nick.ifEmpty { b } }
            // Networks ESPN names: their feeds (the ones whose guide shows this game come first, below).
            for (c in e.casts) {
                val net = BY_KEY[netKey(c)] ?: NETS[c.lowercase(Locale.ROOT)] ?: continue
                val g = g0.firstOrNull { it.id == net.id } ?: continue
                // Other cities' stations are left out: they carry their own market's game.
                for (i in g.feeds) if (idx.ident[i].region != "market") x.feeds.add(Feed(i, false, g, cast = true))
            }
        }
        val all = byKey.values.filter { it.feeds.isNotEmpty() && (it.live || it.start > now - 30 * 60_000L) }
        for (x in all) {
            // Feed order: the guide shows it now, then event channels, then the networks ESPN names; Philadelphia's
            // own station first for a Philadelphia team, else Huntsville's.
            val philly = PHILLY_TEAM.containsMatchIn(x.away + " " + x.home)
            fun rank(f: Feed): Int {
                val r = idx.ident[f.i].region
                return (if (f.live && !f.event) 0 else if (f.event) 1 else if (f.cast) 2 else 3) * 10 +
                    (if (philly && r == "philly") 0 else if (r == "home") 1 else 2)
            }
            val seen = HashSet<Int>()
            x.feedList = x.feeds.sortedWith(compareBy<Feed> { rank(it) }.thenByDescending { idx.score[it.i] }.thenBy { it.i }).filter { seen.add(it.i) }
            val lead = x.feedList[0]
            x.netLabel = if (lead.event) LABEL_TAIL.replaceFirst(LiveOrganizer.cleanName(chs[lead.i].name), "") else lead.net.label
        }
        fun gameTile(x: Cand, mine: Boolean, team: String = "") = HomeTile(
            type = "game", league = x.league, group = x.feedList[0].net.id, title = x.title,
            sub = LEAGUE_LABEL.getValue(x.league) + " · " + x.netLabel,
            badge = if (x.live) (if (mine) "LIVE · ★ MY TEAM" else "LIVE") + (if (x.detail.isNotEmpty()) " · " + x.detail else "") else whenCT(x.start, now),
            live = x.live, mine = mine, start = x.start, feeds = x.feedList.map { it.i }.toIntArray(), team = team,
        )
        val liveFirst = compareBy<HomeTile> { if (it.live) 0 else 1 }

        // ---- My Teams: one tile per favorite team, live games pinned first
        val myTiles = teams.map { t ->
            val x = all.filter { isMine(t, it.league, it.away, it.home) }.sortedWith(compareBy<Cand> { if (it.live) 0 else 1 }.thenBy { it.start }).firstOrNull()
            if (x != null) gameTile(x, true, t.name)
            else HomeTile("team", t.league, "", t.name, LEAGUE_LABEL[t.league] ?: t.league, "No game listed", false, true, Long.MAX_VALUE, IntArray(0), t.name)
        }.sortedWith(liveFirst.thenBy { it.start })
        // ---- Live Games: live now, then starting in the next 3 hours
        val lg = all.filter { it.live || it.start - now <= 3 * 3_600_000L }
            .map { x -> gameTile(x, teams.any { isMine(it, x.league, x.away, x.home) }) }
            .sortedWith(liveFirst.thenBy { if (it.mine) 0 else 1 }.thenBy { if (it.live) LEAGUE_ORDER.getValue(it.league) else 0 }.thenBy { it.start })
            .take(24)

        // ---- "On now" rows: one tile per network, from the feed whose guide has listings
        class NetNow(val i: Int, val cur: Programme?, val list: List<Programme>)
        fun netNow(g: LiveGroup): NetNow? {
            for (i in g.feeds) {
                val list = progs(i)
                if (list.isNotEmpty()) return NetNow(i, list.firstOrNull { it.start <= now && it.end > now }, list)
            }
            return null
        }
        class Scored(val tile: HomeTile, val score: Int, val rank: Int)
        fun topicRow(want: (Topics) -> Boolean, tag: String, cap: Int, extra: (Topics) -> Int = { 0 }): List<HomeTile> {
            val tiles = ArrayList<Scored>()
            for (g in g0) {
                if (g.family || !domestic(g) || g.kind in setOf("sports", "kids", "events", "music")) continue
                val n = netNow(g)
                val tagged = g.net?.tags?.contains(tag) == true
                val rank = g.net?.rank ?: 999
                if (n == null) {
                    if (tagged) tiles.add(Scored(HomeTile("net", "", g.id, g.label, "No guide for this channel", "", false, false, 0L, g.feeds.toIntArray()), 1, rank))
                    continue
                }
                val cur = n.cur ?: continue
                val tp = topics(cur)
                if (!want(tp)) continue
                val feeds = intArrayOf(n.i) + g.feeds.filter { it != n.i }
                tiles.add(Scored(HomeTile("net", "", g.id, g.label, cur.title, "On now · until " + timeCT(cur.end), true, false, cur.start, feeds), 10 + (if (tagged) 5 else 0) + extra(tp), rank))
            }
            return tiles.sortedWith { a, b ->
                if (a.score != b.score) b.score - a.score else if (a.rank != b.rank) a.rank - b.rank else cmpLabel(a.tile.title, b.tile.title)
            }.take(cap).map { it.tile }
        }
        val docs = topicRow({ it.docs }, "docs", 15)
        val mil = topicRow({ it.military }, "military", 15) { if (it.airforce) 20 else 0 }
        val tech = topicRow({ it.tech }, "tech", 15)

        // ---- Primetime Entertainment: curated networks in catalog order; before 7 PM, tonight's 7 PM show
        val hour = hourCT(now)
        val prime = ArrayList<HomeTile>()
        for (n in ORDER) {
            if ("prime" !in n.tags) continue
            val g = g0.firstOrNull { it.id == n.id } ?: continue
            val nn = netNow(g)
            var sub = ""
            var badge = ""
            var live = false
            var feeds = g.feeds.toIntArray()
            if (nn != null) {
                feeds = intArrayOf(nn.i) + g.feeds.filter { it != nn.i }
                val tonight = if (hour < 19) nn.list.firstOrNull { it.start >= now && hourCT(it.start) >= 19 && dayKey(it.start) == dayKey(now) } else null
                if (tonight != null) { sub = tonight.title; badge = "Tonight " + timeCT(tonight.start) }
                else if (nn.cur != null) { sub = nn.cur.title; badge = "On now · until " + timeCT(nn.cur.end); live = true }
            }
            prime.add(HomeTile("net", "", g.id, n.label, sub.ifEmpty { "No guide for this channel" }, badge, live, false, 0L, feeds))
        }
        return listOf(
            HomeRow("myteams", "My Teams", myTiles),
            HomeRow("games", "Live Games", lg),
            HomeRow("docs", "History & Docs On Now", docs),
            HomeRow("military", "Military On Now", mil),
            HomeRow("tech", "Cyber, AI & Tech On Now", tech),
            HomeRow("prime", "Primetime Entertainment", prime),
        )
    }

    /** A-Z kinds for the chips in All Channels. */
    val KINDS: List<Pair<String, String>> = listOf(
        "all" to "All", "sports" to "Sports", "news" to "News", "ent" to "Entertainment", "docs" to "Docs", "movies" to "Movies",
        "local" to "Local", "kids" to "Kids", "music" to "Music", "events" to "Events", "247" to "24/7", "free" to "Free channels",
        "intl" to "International", "other" to "Other",
    )
}

# Platform, Inventory, Buying Experience, Cart/Checkout & Payment — Amazon, Walmart, Target, Best Buy

A comparative deep dive built from public engineering blogs, conference talks, case studies, and technical write-ups from each company (sources listed at the end of each section). Where a detail isn't publicly disclosed, that's noted explicitly rather than guessed at.

---

## 1. Amazon

### 1.1 Platform architecture

Amazon's platform is the origin story most of the industry's "microservices" thinking traces back to. In its first five years, Amazon.com ran on a centralized architecture with direct database access from many application layers — and that direct-access pattern became the bottleneck that limited scaling. The fix, now famous as the internal "API mandate," was to decompose the monolith into **decoupled services that each own their data and expose only a hardened API** — no other service may reach into another's database directly.

The result, as described in Amazon's own architecture writeups: a single page render can call over **150 services**, several layers deep, each with its own SLA for latency and availability. Because business logic per service is kept deliberately lightweight, the hard scaling problem lives almost entirely in the **data layer** — which is precisely why Amazon invested so heavily in building its own storage systems rather than buying off the shelf.

That data-layer investment produced **Dynamo** (2007 paper), an internal, highly-available, eventually-consistent key-value store built specifically because the shopping cart service kept hitting scaling and availability walls on a traditional relational database (Oracle) with primary/replica topology. Dynamo's design principles — partitioning, consistent hashing, tunable consistency, quorum reads/writes — later became **Amazon DynamoDB** (2012), now a public AWS managed service and, notably, one of the most common backing stores for cart and session state across the wider e-commerce industry, not just Amazon.

**Modern stack signals:** Amazon Elastic Compute Cloud (EC2) for compute, S3 for product images/static assets, DynamoDB for high-throughput low-latency state (cart, sessions, inventory views), Lambda + API Gateway for event-driven/serverless service edges, RDS where relational integrity is required. The architecture is explicitly polyglot — different data stores for different consistency/latency tradeoffs, never one database for everything.

**Why it matters for cart/checkout design:** Amazon's own history is the clearest public proof that **cart state is where relational databases break first at retail scale** — high write volume, low transaction complexity, extreme availability requirements (a cart service outage stops all revenue). That's the direct ancestor of the "hard reservation only at checkout, not add-to-cart" pattern discussed in Section 6.

### 1.2 Inventory & fulfillment

Public detail here concentrates on the **physical fulfillment layer** more than the inventory data model (which Amazon does not publish in the granular way Target and Walmart have). What's known:

- **Amazon Robotics** (formed from the 2012 Kiva Systems acquisition) — over a million robots deployed across the network. Drive units (Hercules, Pegasus) ferry inventory pods to stationary or robotic pick stations; vision-guided arms (Sparrow) handle individual SKU picking; multi-story automated storage/retrieval structures (Sequoia) compute the fastest retrieval path to a staging lane.
- **Order routing algorithms** decide, at the moment of purchase, which fulfillment center services the order — weighing current inventory position, distance to the customer, warehouse current workload/capacity, and the promised delivery speed (standard, next-day, same-day, 2-hour). This is a live optimization problem, not a static "closest warehouse" rule — published operations-research work from Amazon describes it as a queuing/allocation problem balancing picker throughput against order deadlines.
- **Next-gen fulfillment centers** (e.g., the Shreveport, LA and Daytona Beach, FL facilities) report materially faster processing — Amazon has stated up to 25% faster fulfillment processing time in AI/robotics-dense sites — which directly expands how many SKUs can be offered on same-day and next-day promise windows.

**What's not public:** Amazon does not publish an ATS (available-to-sell) data model the way Target and Walmart have. Given the DynamoDB lineage, it's a reasonable inference (not a confirmed fact) that inventory position is tracked in a similarly partitioned, high-throughput NoSQL structure rather than a single relational ledger — but treat that as informed inference, not documented fact.

### 1.3 Buying experience (search, personalization, recommendations)

Amazon's recommendation stack is one of the most publicly written-about in the industry, partly because AWS sells productized versions of it (**Amazon Personalize**, **SageMaker**) to other retailers.

- **Classic approach:** item-to-item collaborative filtering (the original "customers who bought this also bought" — one of the most cited early recommender-system papers in the industry came out of Amazon).
- **Modern approach**, per a 2026 AWS engineering write-up on Amazon's own "Everyday Essentials" recommendation platform: a **batch-first architecture** (Amazon Managed Workflows for Apache Airflow for orchestration, SageMaker for model training and vector search, Lake Formation for governed cross-account data access) that was later extended with a **real-time layer using Amazon MemoryDB** for real-time vector similarity search once the team needed to incorporate more real-time behavioral signals — i.e., recommendations don't wait for the next batch cycle when a signal is time-sensitive.
- Deep learning components include matrix factorization (SVD/ALS style) for the base user-item interaction matrix, and sequence models (LSTM/GRU-style RNNs) layered on top to capture short-term intent — what you clicked in the last five minutes matters more than your six-month-old purchase history for "what to show right now."
- **Scale context:** these systems serve tens of thousands of requests per second across global marketplaces, which is why the batch/real-time split exists at all — a pure real-time architecture at that request volume would be prohibitively expensive; a pure batch architecture would feel stale mid-session.

### 1.4 Cart & checkout

The defining artifact here is **U.S. Patent 5,960,411**, "Method and system for placing a purchase order via a communications network" — **1-Click ordering**, filed 1997, granted 1999. It let a returning customer store billing, shipping, and payment information once and then complete a purchase with a single action thereafter, collapsing what had been a multi-page checkout flow.

- Amazon aggressively defended the patent (notably suing Barnes & Noble in 1999) and licensed it selectively (Apple licensed it for the iTunes Store in 2000).
- The patent expired **September 12, 2017** — after which one-click/fast-checkout became an industry-standard pattern, giving rise to companies built specifically to offer it as a service to other merchants (Bolt is the most cited example, founded in direct response to the expiration).
- Academic and industry research cited around the expiration estimated one-click ordering as boosting sales meaningfully (one frequently cited figure is a ~28% lift) purely from removing checkout friction — the clearest public data point tying checkout latency directly to conversion in this space.
- Structurally, 1-Click depends on exactly the durable, low-latency profile store (saved address + saved payment token + default shipping preference) that Section 6's guest/logged-in cart merge design assumes — it's the checkout counterpart to always having a resolved, ready-to-charge identity on file.

### 1.5 Payment

Amazon Pay is the externally-facing piece (lets other merchants accept Amazon's stored payment credentials at their own checkout), separate from Amazon.com's own internal checkout, but both rest on the same idea: **the raw card is entered once, tokenized, and never re-entered.** As a marketplace, Amazon also has to solve **split payment/split settlement** — a single customer order frequently pays out to many different third-party sellers plus Amazon itself for shipping/fees, which is a materially harder payment-orchestration problem than a single-merchant checkout (see Section 6.5 for the general pattern; Amazon's specific internal implementation isn't public).

**Sources:** All Things Distributed (Werner Vogels) on DynamoDB; Amazon Dynamo paper (2007) and DynamoDB paper retrospectives; AWS Architecture/Big Data blogs on Amazon Personalize and the Everyday Essentials recommendation platform; aboutamazon.com on Amazon Robotics; INFORMS Journal on Applied Analytics paper on Amazon fulfillment-center picking algorithms; Knowledge@Wharton and multiple patent-law retrospectives on the 1-Click patent (US 5,960,411).

---

## 2. Walmart

### 2.1 Platform architecture

Walmart Global Tech describes its stack as **cloud-native and deliberately polyglot**: a microservices architecture where each service uses "the language and framework best suited for the functionality" rather than a single company-wide stack — a real-time streaming service might be Node.js/WebSockets, an ML service Python, a REST-exposing service Spring Boot/Java. Current job postings and engineering posts consistently describe: **React/TypeScript** on the frontend, **Node.js and Java/Spring Boot** on the backend, **Kafka** as the primary event backbone, **GraphQL (often Apollo Federation)** as an API aggregation layer across distributed teams, and deployment across a mix of **AWS, GCP, and Azure** (multi-cloud, not single-vendor). Walmart International alone runs this stack across 5,200+ retail units in 23 countries.

### 2.2 Inventory & fulfillment

This is Walmart's most thoroughly publicly documented system, because Walmart engineers have spoken specifically about it at Kafka Summit and in Confluent's engineering blog.

- **Backbone:** Apache Kafka, ingesting from **10+ upstream event sources** (POS scans, receiving events, transfers, online reservations, corrections) into a **canonical, real-time inventory model** spanning roughly **4,700 stores**.
- **Data model:** per-store-per-SKU availability record with three numbers — **on-hand estimate, reserved quantity, and a volatility-tuned safety buffer** — where "sellable" (on-hand minus reserved minus buffer) is the *only* number the e-commerce layer is ever allowed to read. Buffer size is tuned per SKU, higher for high-shrink or high-volatility categories.
- **Write pattern:** regionally **sharded by store**, since a given store's inventory is naturally local and write-heavy (POS scans happen physically at that location); e-commerce reservations, by contrast, are atomic sellable-decrements carrying an **order-scoped hold that expires automatically if checkout stalls**.
- **BOPIS flow specifically:** a pickup order reserves against a *specific* store, not the network; the store's system receives a pick task; if the picker can't physically find the item (a common real-world failure mode — physical counts drift from system counts), an explicit exception flow triggers substitution or refund **plus a correction event that tightens the safety buffer for that SKU/location going forward** — the system learns from its own misses.

### 2.3 Buying experience

Less is publicly documented here than for Target's CORE system, but Walmart Global Tech job postings and blog posts confirm active investment in ML-driven personalization and, as of 2025, integration work with OpenAI/ChatGPT's "Instant Checkout" — allowing purchases to be completed from within a chat interface without leaving it, alongside partners like Etsy and Shopify.

### 2.4 Cart & checkout

Walmart's most distinctive public cart/checkout innovation is **Scan & Go** — customers scan items with their own phone as they shop (first shipped as a standalone app in 2012–2013 pilots, later merged into the main Walmart and Sam's Club apps), building a mobile cart in real time rather than a physical-only cart that gets scanned once at a register. Sam's Club's version now exceeds 9.6 million downloads.

- **Self-checkout strategy has evolved from one-size-fits-all to location-specific**: attended checkout, AI-assisted self-checkout, mobile Scan & Go, and checkout-free pilots are mixed differently per store depending on format and shrink risk.
- Walmart still uses **NCR Voyix** hardware/infrastructure in parts of its self-checkout estate, but has been **progressively bringing software, analytics, and decision-making in-house**, reducing reliance on the third-party platform as the primary innovation driver — a pattern worth noting for any team deciding whether to build or buy POS/checkout software: Walmart's trajectory is toward owning the software layer over time even where it started on a vendor platform.

### 2.5 Payment

**Walmart Pay** lets customers pay directly from the app using a stored default payment method scanned via QR code at the register — functionally similar to Amazon's 1-Click in that the payment method is resolved once and reused, just triggered by an in-store QR scan rather than a web button. Scan & Go's payment layer has had to expand to handle harder cases publicly called out by Walmart's own engineering blog — notably **EBT SNAP** support, which requires the in-app checkout service to interact correctly with government payment processors and split-tender rules (SNAP-eligible items only) alongside standard card rails, plus **Sam's Cash** rewards redemption inside the same flow.

**Sources:** Walmart Global Tech Medium blog (cloud-native architecture); Confluent engineering blog and Kafka Summit talk on Walmart real-time inventory; Walmart careers postings (stack signals); tech.walmart.com blog on Scan & Go evolution; Retail Dive on Scan & Go rollout history; kioskindustry.org on 2025 self-checkout strategy; Retail Dive on OpenAI Instant Checkout integration.

---

## 3. Target

### 3.1 Platform architecture

Target's public cloud narrative is unusually candid, including cost mistakes. The company's cloud journey moved **AWS → IBM Cloud → GCP**, and has since settled into a **hybrid multi-cloud** running **GCP and Microsoft Azure**, layered on top of a large private-cloud/on-prem footprint (stores and distribution centers need local compute regardless of public cloud strategy).

Target built its own abstraction layer, the **Target Application Platform (TAP)**, specifically to avoid deep lock-in to any single cloud's PaaS offering — TAP provides adapters that let the same deployment pipeline target **Linux containers and vSphere VMs in the private cloud, Kubernetes clusters running physically in stores, GKE on Google Cloud, and Container Instances on Azure**, all through one interface. As of the most recent public figures: TAP manages workloads across **1,926 stores, 48 distribution centers, and 3 enterprise data centers**, supporting **5,400+ applications** built by **4,000+ engineers**.

Two things stand out as deliberate design choices, not accidents:
1. **Rewrite, don't lift-and-shift.** Target explicitly chose to re-architect legacy apps into **microservice-based, asynchronous, event-driven** systems rather than move old monoliths as-is — the harder, slower path, taken because a lift-and-shift doesn't actually fix the scaling and coupling problems that made cloud migration necessary in the first place.
2. **Retail seasonality drove the multi-cloud decision directly.** Target's business needs **100%+ overcapacity** during the six-week Thanksgiving-to-Christmas window; running that much idle capacity year-round on a single provider (or on-prem alone) is wasteful, so dynamic workload placement across providers is what actually pays for itself — Target reports roughly **doubling underlying compute utilization** through this approach.

Stores themselves run real compute, not just terminals — Target's **Stores Deployment Interface (SDI)** is a distributed edge-computing platform pushing continuous deployment safely to **1,800+ individual store targets**, running the checkout workflow, in-store product search, team-member tools, and IoT services locally at each store.

### 3.2 Inventory & fulfillment

Target's inventory platform is **event-driven and stateless**: inventory transactions (sales, receipts, transfers, adjustments) stream in continuously, get written to an **append-only ledger**, and the *current* inventory position for every item at every location is recomputed from that ledger in real time — **backed by roughly 20 instances of RocksDB** for the real-time read layer. This is the same "derive current state from an event log" pattern as Walmart's Kafka-based model, implemented with different specific technology.

A real, well-documented Target failure mode reinforces why this matters: the **2019 "Father's Day incident"** — a roughly two-hour outage where store registers nationwide couldn't scan items at checkout — became a formal internal case study on treating the **Target Retail Platform** (the distributed microservice architecture underlying stores) as a genuinely edge-dependent distributed system that needs explicit fault-tolerance design, not an assumption that "the store is just a thin client to a central service."

### 3.3 Buying experience

Target's most specifically documented personalization system is **CORE — Contextual Offer Recommendation Engine** — used to personalize Target Circle loyalty offers per guest.

- Built on a **contextual multi-armed bandit (CMAB)** model layered on top of **matrix factorization** run against a historic guest-offer interaction matrix.
- The team explicitly had to design around **interaction sparsity** — most individual guests have only a handful of historical interactions with any given offer type, which is the classic cold-start problem for collaborative filtering, hence the combination with a contextual bandit that can make context-specific decisions (time of day, recent behavior, channel) rather than relying purely on historical density.
- Optimizes for **guest engagement (offer adds) and redemption**, not just click-through — a deliberate choice to optimize for a business outcome further down the funnel than impressions.

### 3.4 Cart & checkout

Target's headline architectural decision here is **ECCO — Enterprise Cart & Checkout** — a single, **cloud-native microservices platform that powers checkout across Target.com, the Target mobile app, and nearly 2,000 physical stores simultaneously.** Target's own engineering blog calls this unusual for a large brick-and-mortar retailer: most competitors historically ran separate checkout stacks per channel (web checkout, POS checkout, app checkout, as different codebases); Target consolidated to one. The stated payoff: faster feature building/scaling and guests can switch shopping channels mid-journey without the experience breaking.

Because ECCO is "mission-critical" (literally every transaction, every channel, flows through it), Target's public reliability practices for it are specific: periodic automated health checks with alerting, distributed logging for cross-service debugging, and API-level metrics with threshold-based alerts — the standard toolkit for keeping a single-point-of-failure-shaped system from actually becoming one.

Two concrete features built on top of ECCO:
- **Drive Up** (curbside pickup) is powered by a dedicated **Eventing API** — the "I've arrived" signal and staging/handoff workflow discussed generically in Section 6.3 is, at Target, a specific named API product.
- **Express Self-Checkout** (10-items-or-fewer lanes, debuted March 2024) — Target's own published results after about a year: total transaction time improved **~8%** across both self-checkout and traditional staffed lanes, plus measurable NPS improvements on wait time and staff interaction — a rare case of a retailer publishing the actual conversion/experience impact of a checkout-lane design change.

### 3.5 Payment

Target's proprietary payment instrument is the **RedCard** (and its successor/companion, the **Target Circle Card**), a store-branded credit/debit product tied directly into the Target Circle loyalty program — discount-at-purchase plus loyalty point accrual in a single tender type. This is architecturally significant beyond marketing: it means Target's payment layer has to support **loyalty-linked tender types as first-class payment methods**, not just generic card rails — the discount calculation and the payment authorization aren't separable steps the way they are for a generic Visa/Mastercard transaction.

**Sources:** tech.target.com engineering blog (Journey to a Hybrid-Multi-Cloud; Target's Cloud Journey; ECCO Platform; Stores Deployment Interface; Solving for Product Availability with AI; Contextual Offer Recommendation Engine; Hardening the Registers — Father's Day incident postmortem; Building a Flexible Platform to Power Target Drive Up); CIO Dive interview with Target cloud leadership; corporate.target.com fact sheet on Express Self-Checkout results.

---

## 4. Best Buy

Best Buy publishes materially less low-level engineering detail than Target or Walmart — there is no equivalent of tech.target.com or Walmart Global Tech's Medium presence at the same technical depth. What follows is built from case studies, corporate disclosures, and retail-industry retrospectives rather than first-party engineering blog posts; that gap is itself worth noting when deciding how much confidence to place in Best Buy specifics versus Target/Walmart specifics.

### 4.1 Platform architecture

Best Buy is a **Google Cloud** customer (confirmed via a published Google Cloud case study), using GCP for server/VM migration, containerized workloads (GKE/Cloud Run), and batch processing — consistent with the broader industry move toward containerized, cloud-hosted retail platforms rather than on-prem data centers. Best Buy has also run dedicated engineering hubs outside its Minneapolis headquarters (a Seattle technology development office was opened specifically to attract engineering talent for omnichannel transformation work) at a time it had 150+ full-time e-commerce engineers plus several hundred contractors — smaller in disclosed scale than Target's 4,000+ engineer figure, though not necessarily reflective of current headcount.

### 4.2 Inventory & fulfillment

Best Buy is frequently cited as one of the **earliest large-scale BOPIS adopters**, dating to the early smartphone era, with a **45-minute pickup commitment** — aggressive even by today's standards, and notable because it predates most competitors' BOPIS programs by years. The company's **ship-from-store and curbside pickup infrastructure**, built during the Hubert Joly-era turnaround (roughly 2012–2019), is specifically credited in industry retrospectives with performing well under the COVID-19 demand spike, when Best Buy's revenue hit an all-time high (~$52B in fiscal 2021) even with stores physically closed to browsing — the store network kept functioning as a fulfillment network even when it stopped functioning as a showroom.

### 4.3 Buying experience

Best Buy's buying experience is more tightly coupled to **services and membership** than the other three retailers — **Totaltech** (paid membership bundling tech support, Geek Squad services, and member pricing) and the **My Best Buy** loyalty program are the primary personalization/retention levers, alongside generative-AI-summarized customer care interactions (agents get AI-generated call summaries instead of manual notes, reported as freeing agents to focus on the live conversation rather than documentation). Best Buy has publicly reported that **Totaltech members show significantly higher Net Promoter Scores** than non-members, and observed an **increase in online conversion for products under $35** alongside stable membership enrollment — evidence the loyalty tier is doing real retention work, not just discounting.

### 4.4 Cart & checkout

No dedicated public engineering write-up on Best Buy's cart/checkout stack exists at the level of Target's ECCO posts. What's inferable from the BOPIS/ship-from-store history: Best Buy's checkout has had to support **mixed-fulfillment carts (ship-to-home + BOPIS + curbside in the same order)** for longer than most competitors, given how early it built the underlying pickup infrastructure — the checkout-side fulfillment-splitting logic described generically in Section 6.4 is one Best Buy would have had to solve earlier than most.

### 4.5 Payment

Best Buy offers a proprietary **My Best Buy Credit Card** (store financing/rewards card, similar in shape to Target's RedCard) plus standard card/digital-wallet rails, and — per the same disclosures covering Totaltech — a growing **retail media and marketplace** business (Best Buy Ads, a third-party marketplace), which, like Amazon's marketplace, will eventually require split-settlement payment logic across sellers rather than a single-merchant-only payment model. Public detail on Best Buy's specific payment architecture is limited.

**Sources:** Google Cloud customer case study (Best Buy); corporate.bestbuy.com press release (Seattle technology center); Umbrex company profile (Best Buy strategy/technology overview); InfotechLead coverage of Best Buy's digital transformation and Totaltech results; industry retrospective on the Joly-era turnaround and COVID-era fulfillment performance.

---

## 5. Side-by-side comparison

| Dimension | Amazon | Walmart | Target | Best Buy |
|---|---|---|---|---|
| **Cloud strategy** | AWS (built the cloud others use) | Multi-cloud: AWS, GCP, Azure | Hybrid multi-cloud: GCP + Azure, via in-house TAP abstraction | Google Cloud (GCP) |
| **Core architecture style** | Service-oriented, 150+ services/page, since early 2000s ("API mandate") | Polyglot cloud-native microservices | Event-driven microservices, rewritten (not lifted) from legacy, ~4,000 engineers | Cloud-hosted, containerized; less public detail |
| **Signature inventory system** | Not publicly detailed at data-model level; robotics-heavy fulfillment layer is well documented | Kafka-based real-time canonical inventory, 10+ event sources, ~4,700 stores | Event-ledger + RocksDB real-time position engine (~20 instances) | Not publicly detailed; early BOPIS/ship-from-store pioneer |
| **Unifying checkout platform** | Not named publicly the way Target's is | Not named publicly; Scan & Go is the flagship innovation | **ECCO** — one platform for web, app, and ~2,000 stores | Not publicly documented at this level |
| **Personalization approach** | Batch (Airflow/SageMaker) + real-time (MemoryDB vector search) recommendation platform | Present but less publicly detailed; exploring conversational commerce (ChatGPT Instant Checkout) | **CORE** — contextual multi-armed bandit + matrix factorization for Circle offers | Membership/services-driven (Totaltech, My Best Buy) more than algorithmic personalization in public materials |
| **Flagship checkout/payment innovation** | 1-Click ordering (patented 1999, expired 2017) — industry-defining | Scan & Go (self-scan while shopping) + Walmart Pay | Express Self-Checkout (measured throughput/NPS gains); RedCard/Circle Card loyalty-linked tender | Early 45-minute BOPIS; My Best Buy Credit Card |
| **Public engineering transparency** | High (AWS blogs, Dynamo papers) but retail-specific detail is thinner than AWS-product detail | High (Global Tech blog, Kafka Summit talks) | Highest among the four for retail-specific internals (tech.target.com) | Lowest — mostly case studies and press, not engineering blogs |

---

## 6. What this means for a new system design (synthesis)

Across all four, the same underlying architecture recurs regardless of which cloud or language stack sits under it — the pattern from the earlier design doc holds up under this deeper look, with a few refinements worth adding:

1. **Inventory is a derived read model, not a mutable counter, everywhere it's been publicly documented.** Walmart (Kafka) and Target (event ledger + RocksDB) independently arrived at "stream events in, materialize current position, never let a consumer write the position directly." Treat this as close to a solved problem — don't relitigate it, just implement it.
2. **One checkout brain beats one-per-channel, but only a company willing to rewrite (not lift-and-shift) gets there.** Target's ECCO and its explicit "rewrite, don't migrate" cloud strategy are the same decision made twice. If a new system inherits separate web/store/app checkout code today, plan the consolidation as a rewrite, not an incremental port.
3. **Checkout latency has a directly measured revenue relationship.** Amazon's 1-Click history and Target's Express Self-Checkout NPS/throughput data are two independent, *measured* (not just theorized) data points that reducing steps or wait time at checkout converts directly to business results — this justifies investing real engineering effort in checkout speed, not just correctness.
4. **Store compute is not optional at BOPIS/ship-from-store scale.** Target's Stores Deployment Interface (edge compute pushed to 1,800+ stores) and Best Buy's early ship-from-store infrastructure both show that once physical stores are inventory nodes for online orders, they need real local compute and a real deployment pipeline — not just a POS terminal calling a central API over the store's internet connection.
5. **Loyalty-linked payment tenders are a distinct design case.** Target's RedCard/Circle Card and Best Buy's My Best Buy Credit Card both couple discount calculation to the payment instrument itself, not to the promotions engine alone — worth deciding explicitly whether "how you pay" is allowed to change "what you owe" in a new system, because it changes where that logic has to live.
6. **Recommendation/personalization architecture follows the same batch-plus-real-time split everywhere it's documented.** Amazon's batch-first-then-real-time-augmented approach and Target's cold-start-aware contextual bandit (CORE) both reflect the same underlying constraint: pure real-time personalization is too expensive to run for every request at retail scale, and pure batch feels stale — so a hybrid is the practical answer, not a philosophical one.

A deeper investigation could pull primary sources this pass didn't reach — Amazon's and Best Buy's specific inventory data models aren't publicly disclosed the way Walmart's and Target's are, and a broader sweep of patents, conference talks (QCon, re:Invent, NRF Big Show), and SEC/investor technology disclosures could surface more on Best Buy's checkout stack and Amazon's internal ATS design specifically.

(ns game.cost-interfaces)

(defrecord Click [amount])
(defrecord Credit [amount])
(defrecord Trash [amount])
(defrecord Forfeit [amount])
(defrecord ForfeitSelf [amount])
(defrecord Tag [amount])
(defrecord ReturnToHand [amount])
(defrecord RemoveFromGame [amount])
(defrecord RfgProgram [amount])
(defrecord TrashInstalledRunnerCard [amount])
(defrecord TrashInstalledHardware [amount])
(defrecord TrashInstalledProgram [amount])
(defrecord TrashInstalledResource [amount])
(defrecord TrashInstalledConnection [amount])
(defrecord TrashRezzedIce [amount])
(defrecord TrashFromDeck [amount])
(defrecord TrashFromHand [amount])
(defrecord RandomlyTrashFromHand [amount])
(defrecord TrashEntireHand [amount])
(defrecord TrashHardwareFromHand [amount])
(defrecord TrashProgramFromHand [amount])
(defrecord TrashResourceFromHand [amount])
(defrecord NetDamage [amount])
(defrecord MeatDamage [amount])
(defrecord BrainDamage [amount])
(defrecord ShuffleInstalledToDeck [amount])
(defrecord AddInstalledToBottomOfDeck [amount])
(defrecord AnyAgendaCounter [amount])
(defrecord AnyVirusCounter [amount])
(defrecord AdvancementCounter [amount])
(defrecord AgendaCounter [amount])
(defrecord PowerCounter [amount])
(defrecord VirusCounter [amount])

(defprotocol CostFns
  (cost-name [this])
  (label [this])
  (rank [this])
  (value [this])
  (payable? [this state side card]
            [this state side eid card]
            [this state side eid card extra])
  (handler [this state side card actions]
           [this state side eid card actions]
           [this state side eid card actions extra]))
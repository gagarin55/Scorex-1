package scorex.consensus

import org.h2.mvstore.{MVMap, MVStore}
import scorex.block.{Block, ConsensusData, TransactionalData}
import scorex.transaction.box.proposition.Proposition
import scorex.transaction.{Transaction, TransactionModule}
import scorex.utils.ScorexLogging

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}


/**
  * If no datafolder provided, blockchain lives in RAM (useful for tests)
  */
trait StoredBlockchain[P <: Proposition, CData <: ConsensusData, TX <: Transaction[P, TX], TData <: TransactionalData[TX]]
  extends BlockChain[P, TX, TData, CData] with ScorexLogging {
  this: ConsensusModule[P, TX, TData, CData] =>

  val dataFolderOpt: Option[String]

  val transactionalModule: TransactionModule[P, TX, TData]

  private val self: ConsensusModule[P, TX, TData, CData] = this

  //compiler hangs if uncomment: private implicit val consensusModule: ConsensusModule[P, CData, B] = this

  case class BlockchainPersistence(database: MVStore) {
    val blocks: MVMap[Int, Array[Byte]] = database.openMap("blocks")
    val signatures: MVMap[Int, BlockId] = database.openMap("signatures")
    val scoreMap: MVMap[Int, BigInt] = database.openMap("score")

    //if there are some uncommited changes from last run, discard'em
    if (signatures.size() > 0) database.rollback()

    def writeBlock(height: Int, block: Block[P, TData, CData]): Try[Unit] = Try {
      blocks.put(height, block.bytes)
      scoreMap.put(height, score() + blockScore(block))
      signatures.put(height, id(block))
      database.commit()
    }

    def readBlock(height: Int): Option[Block[P, TData, CData]] =
      Try(Option(blocks.get(height)))
        .toOption
        .flatten
        .flatMap { b =>
          val t: Try[Block[P, TData, CData]] = Block.parse[P, TX, TData, CData](b)(self, transactionalModule)
          t.toOption
        }

    def deleteBlock(height: Int): Unit = {
      blocks.remove(height)
      signatures.remove(height)
      database.commit()
    }

    def contains(id: BlockId): Boolean = signatures.exists(_._2.sameElements(id))

    def height(): Int = signatures.size()

    def heightOf(id: BlockId): Option[Int] = signatures.find(_._2.sameElements(id)).map(_._1)

    def score(): BigInt = if (height() > 0) scoreMap.get(height()) else 0
  }

  private val blockStorage: BlockchainPersistence = {
    val db = dataFolderOpt match {
      case Some(dataFolder) => new MVStore.Builder().fileName(dataFolder + s"/blocks.mvstore").compress().open()
      case None => new MVStore.Builder().open()
    }
    new BlockchainPersistence(db)
  }


  log.info(s"Initialized blockchain in $dataFolderOpt with ${height()} blocks")

  override def appendBlock(block: Block[P, TData, CData]): Try[Unit] = synchronized {
    Try {
      val parent = parentId(block)
      if ((height() == 0) || (id(lastBlock) sameElements parent)) {
        val h = height() + 1
        blockStorage.writeBlock(h, block) match {
          case Success(_) => Seq(block)
          case Failure(t) => throw new Error("Error while storing blockchain a change: " + t)
        }
      } else {
        throw new Error(s"Appending block ${block.json} which parent is not last block in blockchain")
      }
    }
  }

  override def discardBlock(): Try[Unit] = synchronized {
    require(height() > 1, "Chain is empty or contains genesis block only, can't make rollback")
    val h = height()
    Try(blockStorage.deleteBlock(h))
  }

  override def blockAt(height: Int): Option[Block[P, TData, CData]] = synchronized {
    blockStorage.readBlock(height)
  }

  override def lastBlockIds(howMany: Int): Seq[BlockId] =
    (Math.max(1, height() - howMany + 1) to height()).flatMap(i => Option(blockStorage.signatures.get(i))).reverse

  override def contains(signature: Array[Byte]): Boolean = blockStorage.contains(signature)

  override def height(): Int = blockStorage.height()

  override def score(): BigInt = blockStorage.score()

  override def heightOf(blockSignature: BlockId): Option[Int] = blockStorage.heightOf(blockSignature)

  override def blockById(blockId: BlockId): Option[Block[P, TData, CData]] = heightOf(blockId).flatMap(blockAt)

  override def children(blockId: BlockId): Seq[Block[P, TData, CData]] =
    heightOf(blockId).flatMap(h => blockAt(h + 1)).toSeq

  override def generatedBy(prop: P): Seq[Block[P, TData, CData]] =
    (1 to height()).flatMap { h =>
      blockAt(h).flatMap { block =>
        if (this.producers(block).contains(prop)) Some(block) else None
      }: Option[Block[P, TData, CData]]
    }
}
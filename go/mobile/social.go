package mobile

import (
	"encoding/json"
	"fmt"

	"github.com/idena-network/idena-go/blockchain"
	"github.com/idena-network/idena-go/blockchain/attachments"
	"github.com/idena-network/idena-go/blockchain/types"
	"github.com/idena-network/idena-go/common"
	"github.com/idena-network/idena-go/common/hexutil"
)

// ContractCall is a call of a contract among the transactions of a block.
type ContractCall struct {
	// Index is the position of the transaction in the block.
	Index int    `json:"index"`
	Hash  string `json:"hash"`
	From  string `json:"from"`
	// Amount is the iDNA sent with the call, as a decimal number.
	Amount string `json:"amount"`
	Method string `json:"method"`
	// Args are the call's arguments, hex encoded, in the order of their indexes.
	Args []string `json:"args"`
}

// ContractCalls returns, as a JSON array of ContractCall, the calls of contract among the transactions
// of a block body (hex, as ipfs_get returns the block's ipfsCid). The node keeps no transaction index for
// blocks it did not execute (fast sync), so the senders are recovered from the signatures here.
func ContractCalls(bodyHex string, contract string) (string, error) {
	data, err := hexutil.Decode(bodyHex)
	if err != nil {
		return "", fmt.Errorf("block body: %w", err)
	}
	body := new(types.Body)
	if err := body.DecodeBytes(data); err != nil {
		return "", fmt.Errorf("block body: %w", err)
	}
	target := common.HexToAddress(contract)
	calls := []ContractCall{}
	for i, tx := range body.Transactions {
		if tx.Type != types.CallContractTx || tx.To == nil || *tx.To != target {
			continue
		}
		attachment := attachments.ParseCallContractAttachment(tx)
		if attachment == nil {
			continue
		}
		sender, err := types.Sender(tx)
		if err != nil {
			continue
		}
		args := make([]string, len(attachment.Args))
		for j, arg := range attachment.Args {
			args[j] = hexutil.Encode(arg)
		}
		calls = append(calls, ContractCall{
			Index:  i,
			Hash:   tx.Hash().Hex(),
			From:   sender.Hex(),
			Amount: blockchain.ConvertToFloat(tx.AmountOrZero()).String(),
			Method: attachment.Method,
			Args:   args,
		})
	}
	res, err := json.Marshal(calls)
	if err != nil {
		return "", err
	}
	return string(res), nil
}
